# VARKA-45: the null-free validity fast path

## 1. Where this came from

`m4/PLAN.md` section 2.17, added after VARKA-32's repaired ceiling
measurement said something the design did not expect. The number that opens this
task is already on the record and is not a fit: `ChronoVectorOps.vectorFourFields`
against `vectorFourFieldsNoValidity` - the same arithmetic, the same guard, with
every destination validity buffer and every `orValidityBitsAt` call removed -
costs 1.50-1.52 ns/row against 0.65-0.67 across three runs (`VARKA-32.md`
7.2, finding 2). **55.6% to 56.7% of that kernel's time is the validity write.**

The emitter's own class doc says the dense side "runs with no validity
bookkeeping at all". It does not. `emitLaneGroup` ends every value output, in
the dense body as in the masked one, with

    aload dstValSeg[o]; iload i; i2l; ldc -1L; iload lanes;
    invokestatic VarkaVectorSupport.orValidityBitsAt

- a call that VARKA-32 established from `-XX:+PrintInlining` does not inline in a
wide loop (212 bytes, `NodeCountInliningCutoff` / `callee is too large`), doing a
bounds check, a four-arm `switch` on `groupBytes(lanes)` and a read-modify-write,
once per lane group per output, to OR a word of all ones into a bitmap the driver
zeroed a moment earlier. On the dense path the dispatcher has already proven every
referenced input null-free, and VARKA-11's invariant (every node maps valid inputs
to valid outputs; there is no null-literal node) makes every value output valid on
every row. The bits are known before the loop starts.

Section 2.17 lists this first of three validity tasks "because each may shrink the
next": 45 takes the dense path's calls away entirely, 46 makes the masked path's
remaining calls inlinable, 47 turns the masked path's per-group writes into one
per word. This file is 45 only.

## 2. What changes

Three sites in `VarkaLoopEmitter`, one new helper in `VarkaVectorSupport`, one
emit option. Nothing on the masked path moves, and nothing in the evaluator.

### 2.1 The helper

    /** Sets exactly the low {@code rows} bits of a bitmap: whole 0xFF bytes for
     *  rows / 8, then the low rows % 8 bits of the last byte, nothing past it. */
    public static void setValid(MemorySegment validity, int rows)

in `VarkaVectorSupport` beside `zero`. It has to set *exactly* `rows` bits, not
fill the last byte, for two reasons that are the same reason: today's dense path
zeroes `(rows + 7) / 8` bytes and then ORs `laneMask(lanes)`-masked words, so the
bits past `rows` in the last byte are zero; `VarkaLoopEmitterSuite.assertSameOutput`
compares dense and masked validity **byte for byte**, and Arrow's
`getNullCount` stops at `valueCount` but nothing says every reader does. Bit-exact
with what the loop produces today is the contract, and it is what makes the
existing suite this task's oracle rather than something to rewrite.

### 2.2 The driver: fill instead of zero, for value outputs, dense only

`emitBody` step (3) zeroes every `dstValidity` in the driver. In the **dense
driver**, a value output (any root that is not a `Cond`) calls `setValid(seg,
length)` instead. A `Cond` root keeps `zero`: its validity slot is the *selection
bitmap*, whose bits mean "known true", not "valid", and the dense body ORs the
comparison mask into it per lane group - that write is real work and stays.

The masked driver is untouched: a masked batch has at least one input with nulls,
and which rows of which output are valid is what the loop computes.

### 2.3 The dense loop and dense epilogue: no OR for value outputs

`emitLaneGroup`'s value-root tail - the six instructions quoted in section 1 - is
emitted only when `!dense`. The `Cond` branch above it keeps its OR in both
bodies. `emitLaneGroup` is shared by the loop and the epilogue (the epilogue calls
it once with `s.epilogueMask` set, selecting `orPartialValidityBitsAt`), so one
condition covers both; the dense epilogue's partial-group tail rows are inside
`length` and are covered by the driver's fill.

### 2.4 Behind an option, like every lowering change before it

`VarkaEmitOptions` gains `boolean denseValidityOnce` beside `shareChronoPrefix`,
with `withDenseValidityOnce`, a `canonical()` rendering, and the usual
non-default-only rule in the shape hash. `false` reproduces today's bytes exactly.
This is the third time this file has done this (`FloorMod7`, `shareChronoPrefix`)
and for the same reasons: the differential in section 4 compares the two variants
bit for bit, and the benchmark prices the change as an A/B in one run rather than
against a number from another day. Which value `DEFAULTS` carries is section 5's
deliverable, though section 6 says what is expected.

### 2.5 What this does not touch

* **Arrow's "no validity buffer at all" for `null_count == 0`.** Section 2.17
  mentions it. Arrow Java's `BaseFixedWidthVector` always allocates the buffer and
  `allocateVector` in `VarkaKernelEvaluator` always hands its address to the
  kernel; skipping the allocation is an evaluator change with no measured prize,
  and a bitmap of all ones is a `null_count` of zero to every Arrow reader. Out.
* **The masked path.** Even where an individual output's inputs are all null-free
  inside a masked batch (one dirty column among clean ones), the masked body ORs
  per group as today. Filling *that* output once is a real generalization - the
  driver knows every input's null count - but it puts a per-output branch into the
  masked body and its prize is unmeasured. Recorded in section 8 with the shape
  that would justify it, not built here.
* **VARKA-46 and VARKA-47.** Nothing here makes a helper inline or coarsens a write.

## 3. Files

* `sql/varka/engine/.../vector/VarkaVectorSupport.java` - `setValid`, beside
  `zero`, whose doc ("every kernel calls this before writing anything, because the
  loops only OR bits in") is qualified to say the dense driver now fills instead.
* `sql/catalyst/.../codegen/varka/VarkaEmitOptions.java` - the option.
* `sql/catalyst/.../codegen/varka/VarkaLoopEmitter.java` - `emitBody` step (3),
  `emitLaneGroup`'s value-root tail, the class doc's "no validity bookkeeping at
  all" sentence (true after this task; today it is not), `emitBody`'s javadoc
  ("only the driver zeroes the destination validity") and step (3)'s comment.
* `sql/varka/engine/src/test/.../VarkaVectorSupportTest.java` (or the engine
  module's nearest existing test) - `setValid` at every `rows % 8`, and at 0.
* `sql/catalyst/src/test/.../VarkaLoopEmitterSuite.scala` - section 4.
* `sql/catalyst/src/test/scala/org/apache/spark/sql/VarkaEmitterParityBenchmark.scala`
  and its committed results file; `VarkaThroughputBenchmark` and its file
  (section 5).
* `docs/sql-varka.md` - the dense-body bullet, and the numbers paragraph if a
  quoted figure moves; `README.md` likewise.
* `m4/PLAN.md` row 45 to DONE; section 2.17's task-45 paragraph swept;
  `SKILLS.md` (section 7).

## 4. Correctness, by construction rather than by new oracles

The claim is narrow: the dense path writes the same bits it writes today, from a
different place. So the tests are mostly the existing ones run under both option
values, plus two that pin the narrowness.

1. **Every `checkMatrix` case, both option values.** The suite already drives
   every node type over every null pattern and every remainder length, asserting
   per-row validity and value against the reference evaluator, and
   `assertSameOutput` compares validity byte for byte. Add the option to the
   existing dense/masked agreement tests (`"the masked body agrees with the dense
   body on null-free data"`, and VARKA-21's mask-root twin) and to one calendar
   case, so the fill is checked against the masked body that never changed.
2. **Bit-exactness of the fill itself.** For lengths 1, 7, 8, 9, 15, 16, 17, 63,
   64, 65, 1000 and 4095, a null-free value output under `denseValidityOnce`
   is byte-identical to the same output under today's path - including the bits
   past `length` in the last byte. This is the test that fails if `setValid`
   fills whole bytes.
3. **The selection bitmap is untouched.** A `Cond` root on a null-free batch under
   both option values, byte for byte: the existing task-21 tests already assert
   the selection rule; the addition is the option. This is the test that fails if
   2.2 fills a `Cond` root.
4. **The masked path emits the same bytes.** `codeSize` of `loopMasked0` and
   `epilogueMasked` is identical under both option values for a calendar shape and
   a plain chain; `codeSize` of `loopDense0` is strictly smaller under the new
   value. Same technique as VARKA-32's "byte for byte as it was" test, and the
   guard that keeps this task off the masked path.
5. **`setValid` on its own**, in the engine module: `rows` from 0 to 17 over a
   pre-filled `0xFF` buffer of `(rows + 7) / 8 + 1` bytes, asserting exactly the
   low `rows` bits set and the byte after the bitmap untouched.

**Pinned oracles are not expected to move.** The line map's decoding key indexes IR
nodes, and no node is added or removed - only a store call after the last one; the
shape hash renders `DEFAULTS` as empty whichever way the default goes. If either
moves it is a finding for section 7, not a re-pin.

## 5. The measurement

`VarkaEmitterParityBenchmark`, existing cases, no new shapes: the point is what
this does to kernels that already exist. Both option values in one run, five
iterations over two-second windows, idle machine, three runs, ratios under 1.3x
compared by minimums (`VARKA-14.md` 2.1), at both widths (`-XX:MaxVectorSize=16`
through the forked test JVM's `Test / javaOptions`, not `JAVA_OPTS`).

The rows that decide the default, and the direction each is required to move:

| row | today (M rows/s) | must |
|---|---|---|
| `year, null-free` | 1823.4 | rise |
| `year, mixed nulls` | 1717.4 | not move |
| `year+month+day+quarter, shared (1 loop method), null-free` | 799.8 | rise |
| `..., shared (1 loop method), mixed nulls` | 772.2 | not move |
| `dayofweek, for scale, null-free` | 7665.1 | rise |
| `year+month+day+quarter, shared, chunk 64 / 63, null-free` | 396.8 / 400.1 | rise or not move |
| the task-17 budget rows (mixed nulls) | 4436.3 / 3149.6 | not move |

**The "today" column above is stale; read it off the committed file when this
task is picked up.** VARKA-48 regenerated
`VarkaEmitterParityBenchmark-jdk25-results.txt`, which had not been regenerated
since VARKA-32 step B1, and every single-field calendar row in it moved by about
a fifth - VARKA-51's removal of the per-extraction range guard, which shipped
without a regeneration. `year, null-free` is now 2166.5 and `year, mixed nulls`
2046.0; the four-field shared rows and the `dayofweek` scale row did not move.
The *directions* required above are unaffected, since they are about what this
task's own change must do.

The chunk-64 and chunk-63 rows are there because the fill is paid once per
*batch*: at 64 rows it is eight bytes against four lane groups, at 4096 it is 512
bytes against 256, and the ratio between them says whether the fill ever costs
more than the ORs it replaces. It should not at any batch size a real query runs -
`setValid` is a `fill` plus one byte store - but that is a claim, and chunk 63 is
where it would show.

`-XX:+PrintInlining` on the dense four-field kernel under the new value, confirming
no `orValidityBitsAt` call site remains in `loopDense0` at all - the diagnostic
2.17 names as VARKA-46's deliverable, read here for the dense path because "the
call is gone" is a fact the JVM can state and a timing cannot.

The default flips to `true` if every "rise" row rises at both widths and no "not
move" row moves past run noise. `VarkaThroughputBenchmark`'s null-free cases are
regenerated in the same commit, since docs quote them; they are expected to move
less than the parity rows (the evaluator and Arrow path sit on top of the kernel),
and how much less is worth having a number for.

## 6. Predictions, registered before the run

1. `year, null-free` reaches **2300 to 2900 M rows/s** (1.25x to 1.6x from 1823).
   Reasoning: `1 / 1823 = 0.549 ns/row`; the four-field ceiling's validity write
   was 0.84 ns/row over four outputs, ~0.21 per output, of which the per-group call
   is most and the four `zero()` calls (which become fills, same cost) are the
   rest. Confidence: medium - the emitted dense kernel is not the masked ceiling
   this was measured on, and 7.2's finding 1 says the two differ by 1.15-1.20x.
2. `year+month+day+quarter, shared, null-free` reaches **1000 to 1300** (from
   799.8). Same arithmetic, four outputs' calls removed against a shared prefix.
   Confidence: medium.
3. **The 128-bit gain is larger than the AVX-512 gain**, for `year` at least
   1.4x. A 4-lane group makes four times as many calls per row as a 16-lane one,
   and the call's cost does not shrink with the width. This is the same argument
   2.17 makes for VARKA-46; it applies to 45 first. Confidence: medium-high.
4. `dayofweek, null-free` rises **less in ratio than `year`** - it is ~14 ops to
   `year`'s ~45, so its validity call was a larger share of a smaller body, but
   its stores and loop control are too; expect 1.15x to 1.4x. Confidence: low;
   this is the row most likely to surprise in either direction.
5. No "mixed nulls" row moves. Confidence: high - the masked path's bytes are
   asserted identical (4.4), so any movement is run noise or a JIT artifact and
   goes to `SKILLS.md`'s bimodality entry, not to this task's ledger.
6. Chunk 63 and 64 do not regress against chunk 4096's ratio; the fill's cost per
   batch stays under one lane group's worth of ORs. Confidence: high.
7. No pinned oracle moves. Confidence: high (section 4).
8. **For VARKA-32 B2, not for this task:** B2's shared-vs-separate ratios rise once
   this lands, per `VARKA-32.md` 10.6 prediction 6 - the removed cost is the
   same on both sides of that ratio and the shared side is the smaller one.

## 7. What gets recorded

* This file's section 8 (outcome): the tables from section 5, predictions 1-7
  scored, `-XX:+PrintInlining`'s line for `loopDense0`.
* `m4/PLAN.md`: row 45 DONE; section 2.17's task-45 paragraph rewritten
  in the past tense with the number, and its forecast for 46 and 47 re-examined
  against what 45 left - 2.17 says 45 "does nothing for the masked one", which
  section 5's "not move" rows will have confirmed or not.
* `SKILLS.md`: a bullet under the emitter section - a store the loop repeats per
  group with a constant operand is a fill the driver should do once, and the
  class doc's claim that the dense side did no validity bookkeeping survived from
  VARKA-11 to VARKA-45 because nothing measured it until VARKA-32 put a no-validity
  kernel beside a validity-writing one.
* `docs/sql-varka.md`: the dense-body bullet gains the fill; the headline numbers
  requoted from the regenerated files if any quoted figure is one that moved.

## 8. Risks, and what stays parked

1. **A reader that looks past `valueCount`.** `setValid` is bit-exact with today's
   output by construction (2.1, test 4.2), so this risk is about the *test*
   being wrong, not the code: if 4.2 is written with a whole-byte fill as its
   expectation, it passes for the wrong reason. Write the expectation as "run
   today's path", never as a computed byte.
2. **The dense driver fills an output nobody writes.** Today an output no lane
   group reaches reads as all-null (the emitter invariant the driver's `zero`
   exists for). In the dense body every value output is reached on every row, so
   the invariant is preserved by a different mechanism - but only because VARKA-11's "valid in, valid out" holds for every node. A future node that can
   produce a null from valid inputs (a checked cast, an ANSI-mode arithmetic
   that nulls on overflow under `try_*`) breaks the dense path's premise, and
   this task's fill would then publish a valid bit over an undefined value. That
   node already cannot use the dense body as it stands (the dispatcher's
   null-free test would be lying about its output); the guard belongs with the
   node, and this section is where whoever adds one finds the reason. VARKA-30
   (`try_add`/`try_subtract`) is the first place this will come up.
3. **The fill is not free at tiny batches.** Section 5's chunk-63/64 rows are the
   check. If it shows - it should not - the fix is the same as the driver's
   existing all-null shortcut shape: fill only when `length` is above a threshold,
   OR per group below it. Not built unless the number asks.
4. **Parked: per-output fill inside a masked batch** (2.5). One dirty column in a
   projection currently puts every output on the masked path, ORing per group
   even for outputs that never read the dirty column. The driver has every
   input's null count and the analysis has every output's column set, so "this
   output is null-free in this batch" is one AND per output per batch. The prize
   is real on mixed projections and unmeasured; the cost is a branch per output
   in the masked body. Goes to the milestone's debt register with this shape.

## 9. Sequencing

Off `master`, one branch, two commits, each green on the standing gate (both
widths, both modules, `dev/lint-java`, `dev/scalastyle`, `catalyst/doc`, and the
engine module's own Maven build for the helper).

1. **The mechanism, default off.** `setValid` and its test; the option; the three
   emitter sites; section 4's tests, all green with `denseValidityOnce = false`
   producing today's bytes (asserted by `codeSize`) and `true` producing the same
   output bits (asserted by the matrix and 4.2). No committed number and no
   pinned value moves in this commit, and nothing is requoted.
2. **The measurement and the default.** Section 5's runs at both widths; the
   default flipped if the rows move as required; parity and throughput files
   regenerated once; docs, README, milestone row, 2.17 and `SKILLS.md` updated;
   this file's section 8 written with predictions scored.

Two commits rather than one so that the byte-identity claim in commit 1 is
reviewable on its own: a reviewer can see that `false` changed nothing and `true`
changed only the dense driver and the dense loop tail, before any benchmark
number asks to be believed.

## 10. Explicitly out of VARKA-45

* VARKA-46 (inlinable width-specialised helpers for the masked path) and VARKA-47
  (one validity write per 64 rows). Both stay gated on what this task leaves, per
  2.17's ordering.
* Skipping the output validity buffer's allocation in the evaluator (2.5).
* Per-output fill inside a masked batch (8.4; debt register).
* Any change to `zero`, to the masked driver, or to the `Cond` root's selection
  write.

## 11. Outcome

The mechanism shipped as planned and the default is flipped. Both sides ran
adjacent in one regeneration, five iterations over two-second windows, at both
widths, on the tree merged with VARKA-53 (so the Neri-Schneider month block sits
under both arms), AMD Ryzen AI 9 HX PRO 370, OpenJDK 25.0.4.

### 11.1 The numbers

M rows/s, higher is better. "Per group" is the older lowering, kept as the
reference variant.

AVX-512 rows are read off the committed results file, which is the run the
default was flipped in; 128-bit rows are from the narrow-width run recorded here
rather than committed, as this benchmark's own instructions prescribe.

| row | filled once | per group | gain |
|---|---|---|---|
| `year`, null-free, AVX-512 | 2769.1 | 2201.4 | +26% |
| `year`, null-free, 128-bit | 1055.7 | 746.9 | +41% |
| four-field shared, null-free, AVX-512 | 1531.0 | 821.3 | +86% |
| four-field shared, null-free, 128-bit | 705.0 | 282.9 | +149% |
| `dayofweek`, null-free, AVX-512 | 8413.0 | 7508.5 | +12% |
| `dayofweek`, null-free, 128-bit | 3935.3 | 2687.8 | +46% |
| `year`, mixed nulls, AVX-512 | 2206.0 | 2224.5 | -0.8% |
| `year`, mixed nulls, 128-bit | 747.8 | 746.2 | +0.2% |
| four-field shared, mixed nulls, AVX-512 | 841.6 | 825.4 | +2.0% |
| four-field shared, mixed nulls, 128-bit | 280.3 | 280.6 | -0.1% |

The figures moved between the three runs this task made - the run that decided
the flip (`year` 2749.4/2234.1, +23%), the pre-merge run that first committed it
(2757.0/2185.6, +26%; four-field 1477.4/812.7, +82%; `dayofweek` 8378.1/7768.1,
+8%) and the merged-tree run above - which is the ordinary spread of
regenerations plus, for the four-field shape, VARKA-53 now sitting under both
arms, and is why the file rather than a remembered number is what gets quoted.

Every "must rise" row rose and every "must not move" row stayed inside 2%, so
section 5's condition for flipping the default is met and `DEFAULTS` carries
`denseValidityOnce = true`.

For scale: the shared four-field kernel at 1531.0 now beats
`ChronoVectorOps.vectorFourFields`, the hand-written ceiling this project spent
VARKA-32 chasing (672.5 in the same run), by **2.3x** at AVX-512.

### 11.2 Predictions, scored

1. **Held.** `year, null-free` reaches 2769.1, inside the predicted 2300-2900.
   (The band was set against 1823, a figure section 5 already flagged as stale;
   the base at the time of this run was 2201.4, and the result lands in the band
   regardless.)
2. **Beaten.** The four-field shared row was predicted at 1000-1300. It reaches
   1531.0, past the top of the band. Removing four calls per lane group against
   a prefix that sharing had already collapsed leaves validity a larger share of
   what remained than the arithmetic allowed for.
3. **Held, and it is the cleanest hit in the set.** The 128-bit gain is larger
   for every shape - +149% against +86%, +41% against +26%, +46% against +12% -
   because a four-lane group makes four times as many calls per row as a
   sixteen-lane one and the call's cost is per call, not per lane. This is the
   argument `m4/PLAN.md` 2.17 makes for **VARKA-46**, and it is now
   measured rather than reasoned.
4. **Held at AVX-512, missed at 128-bit.** `dayofweek` was predicted to rise
   less in ratio than `year`: it does at AVX-512 (+12% against +26%) and does not
   at 128-bit (+46% against +41%). The plan called this "the row most likely to
   surprise in either direction" and gave it low confidence, which was right. A
   ~14-op body at four lanes is dominated by per-group overhead, so removing a
   per-group call is proportionally *more* of it, not less - the reasoning that
   made it rise least at sixteen lanes inverts at four.
5. **Held.** No mixed-null row moved: four controls, all inside 2%, at both
   widths. This is the prediction that validates the design rather than the
   win - the masked bodies are asserted byte for byte identical under the
   option, and the timings agree with the assertion.
6. **Held.** Chunk 63 and 64 did not regress against chunk 4096's ratio.
7. **Held.** No pinned oracle moved. `DEFAULTS.canonical()` is empty whichever
   way the default goes, and the line map indexes IR nodes, of which this task
   adds none.
8. VARKA-32 B2's ratios: for that task to re-examine, not this one.

### 11.3 What this leaves for 46 and 47

2.17 sequenced these three "because each may shrink the next", and 45 has now
sized what it left. The masked path is untouched by construction, so 46's and
47's prize on it is exactly what 2.17 estimated. What changed is the dense
path: there is no longer a per-lane-group validity call there at all, so 46's
inlinable helpers and 47's per-word accumulation now have **only** the masked
path to improve. That makes them narrower tasks than 2.17 assumed and their
value should be re-derived against the masked rows before either is scheduled.

Prediction 3 is the one that carries forward: it is 46's own argument, and it
held at both widths here.
