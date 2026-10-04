# VARKA-24: the scalar tail, mask interrogation, compaction

Milestone 4's first task, and the reason it is first (`m4/PLAN.md`
section 2.1, task row 24, catalogue item 11): the emitter's remainder handling
was a *second full walk of the IR*, so every node type VARKA-26 to VARKA-30 add would
otherwise have been written twice - once as vector bytecode, once as scalar -
and kept in agreement row for row. The gate is the standing one, with the
pinned oracles doing unusual duty: this task changes how the emitted class is
structured without changing what it computes, so both committed shape hashes
and the committed line map must come out byte-identical (section 5).

## 1. Decisions, and who made them

Settled with the owner during planning, before any code:

1. **One epilogue method per density.** `epilogueDense` and `epilogueMasked`
   replace `tailDense` and `tailMasked` one for one: same method count, same
   driver call sequence, `GROUP_BUDGET` untouched. The epilogue runs one pass
   per batch, so the budget - which exists to keep a *hot* method's C2 compile
   cheap - has nothing to bound there, and the tail it replaces was already one
   method covering every output. Rejected: an epilogue per output group (turns
   one cold method into N and emits the vector walk twice per group), and
   appending the epilogue to each hot loop method (doubles each hot method's op
   count against `GROUP_BUDGET = 16`, the one option that touches the hot path).
2. **The `anyTrue`/`allTrue` deliverable lands at batch level first** - the
   filter's `count == 0` and `count == len` paths, where measurable work
   disappears. The per-lane-group version is measured and then shipped or
   declined with a task-16 reason.
3. **`DateVectorOps` converts too.** The hand-written kernels are the reference
   later lane types are copied from, so the idiom is written there as well as
   generated.

## 2. What the scalar tail actually cost

Milestone 4's open question 3, and the milestone plan required it answered
*before* the emitter work. Answering it meant building the measurement, not
just running one:

**No committed harness in this project had ever executed a scalar tail row.**
`VarkaEmitterParityBenchmark` ran one call over 1,000,000 rows;
`DateVectorOpsBenchmark`'s `@Param` sizes are 32, 10000 and 1000000; Spark's
default `COLUMN_BATCH_SIZE` is 4096. Every one of those is a multiple of 4, 8
and 16, so `loopBound == length` at every lane count this engine runs at and
the tail was dead code under measurement. Being lane-aligned by accident is the
kind of thing a harness does not tell you, which is why it is written down here
and in `SKILLS.md`.

The new `batch-length alignment` section of `VarkaEmitterParityBenchmark`
drives the same total row count through aligned and unaligned chunks at two
chunk sizes. Within a pair the call counts match, so the difference is the tail
and nothing else; between the pairs the call count rises 64-fold, so the
difference prices the per-call prologue. Committed numbers, best times, one run:

| shape | aligned | unaligned (lanes-1 tail rows) | delta per call | per tail row |
|---|---|---|---|---|
| depth-4 chain, 4096/4095, null-free | 199.0 ns/call | 222.7 | 23.6 ns | 1.58 ns |
| depth-4 chain, 4096/4095, mixed nulls | 265.5 | 285.0 | 19.5 ns | 1.30 ns |
| depth-4 chain, 64/63, null-free | 14.7 | 34.0 | 19.3 ns | 1.29 ns |
| depth-4 chain, 64/63, mixed nulls | 17.4 | 37.0 | 19.6 ns | 1.31 ns |
| `dayofweek`, 64/63, null-free | 16.5 | 47.0 | 30.5 ns | 2.03 ns |
| `dayofweek`, 64/63, mixed nulls | 17.7 | 54.7 | 37.0 ns | 2.47 ns |

The cross-check is that the **delta is the same whether the batch is 4096 rows
or 64** - 19-24 ns for the chain either way - which is what a fixed
per-partial-batch cost looks like and what a measurement artefact does not.

So the answer, in three registers:

* **A scalar tail row costs 1.3-1.6 ns** on the depth-4 chain and 2.0-2.5 ns on
  `dayofweek`, where the vector loop costs 0.73 ns per *lane group* of 16 rows.
* **A partial batch pays 19-24 ns for its tail** (30-37 ns for `dayofweek`),
  which is **~11%** of a 4096-row batch's kernel time and **~135%** of a
  64-row batch's - and **0%** of any lane-aligned batch, which the default
  4096 always is.
* **End to end it is nothing.** `VarkaFilterBenchmark` and
  `VarkaThroughputBenchmark` measure 5-25 ns/row once Arrow access and the row
  boundary are included, so a 4096-row batch takes 20-100 microseconds and the
  tail is 0.02-0.1% of it.

The same section prices the per-call fixed cost at **11.8 ns** - segment
wrapping, species read, `loopBound`, the literal hoist, the dispatch, and the
call into a tail method that then re-does all of it before discovering it has
no rows. At 4096 rows that is 0.003 ns/row.

**The honest conclusion, which the milestone plan predicted: this task is not a
speed change.** The tail is free on aligned batches and cheap on unaligned
ones. The case is entirely the emitter's per-node surface, and it is made in
section 4 as a number rather than an adjective.

One caveat on the run, recorded rather than hidden: regenerating this file
moved the pre-existing sections by 10-30% against their previously committed
values in both directions, on a machine that was quiet but not dedicated. The
conclusions above rest on within-run ratios of 2.2x-3.1x, not on cross-run
absolutes, and the two independent chunk ladders agree to within 0.3 ns on the
derived per-row cost.

## 3. Predictions, registered before the measurements

Written into the approved plan before any number was taken; scored in
section 6.

1. The tail's runtime share is ~0 at aligned batch sizes and under ~2-4% at
   unaligned ones; the case for the change is emitter surface, not speed.
2. Emitted class size changes by less than +-20% for most shapes and no shape
   grows more than +50%.
3. `compress` recovers less than the ~1-3 ns/row ceiling VARKA-21 committed,
   because it makes the copy faster rather than removing it: predict 1-2 ns/row
   at 100% selectivity, so the compacting rung moves from 2.7x to at most
   ~3.2x, and possibly not at all outside AVX-512.
4. The `count == len` forwarding path is worth more at that rung than
   `compress` is, because it removes the copy entirely.
5. No engine JMH number moves, every committed size being lane-aligned.

## 4. The masked epilogue

The insight that kept this small: the epilogue is the existing vector body with
three substitutions, not new code.

| | loop method | epilogue method |
|---|---|---|
| `i` | `for (i = 0; i < loopBound; i += lanes)` | `i = loopBound`, one pass, guarded by `loopBound < length` |
| lane count | `species.length()` | `length - loopBound` |
| loads and stores | unmasked | masked with `species.indexInRange(loopBound, length)` |

So `emitVectorLoop`'s body was lifted into `emitLaneGroup`, which the loop
calls per iteration and the epilogue calls once. Everything between a load and
a store stays unmasked, exactly as in the loop, and the masked overloads are
selected by one nullable slot (`Slots.epilogueMask`) rather than by a flag
threaded through the walk.

The masked load is required, not preferred: the data segment is sized to
`length * 4`, so an unmasked load of the last partial group would leave the
segment. Its consequence is now an invariant in the emitter's class doc -
**inactive lanes read `0`, so no operation in the walk may trap on `0`**. It
holds for free today (the mod-7 lowerings divide by the constant 7; add, sub,
compare, blend, max, min and the shifts are total) and VARKA-30's ANSI division
is the first thing that will break it.

### The bug the design missed, and what it cost

Planned as "`lanes` becomes the remainder". That is wrong, and wrong silently.
`VarkaVectorSupport.validityBitsAt` and `orValidityBitsAt` take a lane
*width*, not a row count: the width decides how many bytes the access spans,
through `groupBytes(lanes) = max(1, lanes / 8)` and a switch over 1, 2, 4 and 8
bytes. A nine-row group asks for `groupBytes(9) == 1`, so the helper reads one
byte and reports the ninth row null. `VarkaLoopEmitterSuite` caught it
immediately at `length=9` - the boundary-straddling `lengths` list doing
exactly the job it exists for.

The write side is worse than a wrong answer. A whole-group write at the last
group can run off the bitmap: at `length = 17` the bitmap is three bytes and a
16-lane group at row 16 stores a short across bytes 2 and 3.

So the task added the partial-group pair -
`VarkaVectorSupport.partialValidityBitsAt` and `orPartialValidityBitsAt` -
which walk the `(row % 8 + rows + 7) / 8` bytes the group actually spans, and
the emitter picks between the pairs on `epilogueMask != null`. Keeping them
separate rather than generalizing the originals is deliberate: the whole-group
form is called once per lane group in the hot loop, and a byte loop there would
be paid for a cold path's convenience.

### What it bought

* `VarkaLoopEmitter.java`: **2110 lines to 1914** (+170, -366). One of the five
  per-node switches over the sealed IR hierarchy is gone, which is the number
  that matters for VARKA-26 to VARKA-30: a new node type is now written once.
* Emitted class size, seven shapes, before and after:

| shape | before | after | delta |
|---|---|---|---|
| `addDays` | 4292 | 4386 | +2.2% |
| depth-4 chain | 4855 | 4903 | +1.0% |
| depth-16 chain | 6931 | 7027 | +1.4% |
| `dayofweek` | 5171 | 5224 | +1.0% |
| filter `Compare` root | 4341 | 4267 | -1.7% |
| every node type | 8215 | 8031 | -2.2% |
| 4 x depth-16 (64 ops) | 21574 | 21817 | +1.1% |

  Prediction 2 said +-20% with no shape past +50%; the answer is -2.2% to
  +2.2%, and the direction is the predicted one - narrow shapes grow a little,
  node-rich shapes shrink, because the scalar walk cost grew with node count
  while one masked vector body does not.

## 5. What did not move: the milestone plan corrected

`m4/PLAN.md` section 2.1 says VARKA-24 "changes emitted bytes, so the
two pinned shape hashes and the pinned line-map literal move, and are
regenerated under their own update rule - the one task in the spine where that
is expected rather than alarming."

**That is wrong, and the correction matters more than the error.**
`VarkaShapeCacheImpl.shapeHash` is SHA-256 over `VarkaVectorIR.canonical` of
the outputs plus `numInputs|numLiterals` plus `options.canonical()`, which is
empty for `DEFAULTS`. This task adds no IR node and no emit option, so
`586434f9b9739c40` and `612c94d132690dc2` cannot move. The line map renders
`analysis.topoOrder` through `canonicalShallow`, a property of the IR and its
schedule rather than of how many bodies consume it, so `pinnedLineMap` cannot
move either.

Both held, untouched. That makes them this task's **behaviour-preservation
proof** rather than its collateral - the same role they played in VARKA-23. A
change that deletes one of the emitter's two lowerings of every node type and
leaves both committed hashes byte-identical has demonstrated something; if
either had moved, that would have been a bug, not a regeneration.

The milestone file is corrected in place, per the rule that a plan is a record:
the sentence is rewritten with what was actually found, not deleted.


## 6. The hand-written kernels, and a harness that could not be trusted

The owner chose during planning to convert `DateVectorOps` too, so that later
lane types are copied from a kernel that shows the idiom. Getting to an answer
took four measurement rounds and produced a finding larger than the conversion.

**Round 1-3, in the engine's JMH harness.** Three shapes were written, each
A/B'd against the unconverted kernels in fresh JVMs with interleaved rounds:

1. **One mask helper shared by the loop and the epilogue** - 24-50% off the
   mixed-null throughput of all three kernels. The helper contains
   `partialValidityBitsAt`, which contains a byte loop, and the hot loop has to
   inline it.
2. **The epilogue inline in the kernel method** - 8-40%, in proportion to how
   often the epilogue calls the mask helper: `vectorAddDays` calls it once and
   lost 12%, `vectorDateDiff` calls it twice and lost 39%.
3. **The epilogue in its own sibling method** - the house rule's shape, and the
   emitter's. Best of the three and apparently still a loss: 4-8% at 1,000,000
   rows and 20-31% at 32.

On that evidence the conversion was reverted.

**Then the owner asked whether forced inlining had been tried**, and the answer
changed the conclusion rather than the code. Under
`-XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=inline,jdk/incubator/vector/*.*`
the *unconverted* kernels ran 50-190% faster in the JMH harness
(`vectorDateDiff` NULL_FREE at 10000 rows: 435 to 1276 ops/ms). The same flag
applied to the catalyst parity benchmark changed nothing at all - hand-written
`date_add` 5563 to 5542, emitted 18211 to 18001, all within 1%.

A flag that is worth 190% in one harness and 0% in another is not a speedup; it
is a measurement fault. The engine's JMH runs `forks = 0`, in the surefire JVM,
*after* the JUnit suites, so the kernels are compiled against profiles those
suites polluted - which also explains why that harness has a case
(`scalarSubDays.MIXED_NULL`, code this task never touched) that swings 3x
between runs. **Every number in rounds 1-3 was measured in a degraded JIT
state.**

**Round 4, in the clean harness, with a control.** The parity benchmark
exercises `DateVectorOps` directly as its hand-written cases, in a fresh JVM,
and in the same runs it measures the *emitted* loops, which this change cannot
touch. Two interleaved rounds per arm, best times:

| case | unconverted | converted | delta |
|---|---|---|---|
| `date_add` kernel, null-free | 6034.3 | 5707.8 | -5.4% |
| `date_add` kernel, mixed nulls | 4722.4 | 4603.5 | -2.5% |
| `datediff` kernel, null-free | 4157.3 | 3925.5 | -5.6% |
| `datediff` kernel, mixed nulls | 3012.3 | 2653.0 | -11.9% |
| *control:* `date_add` emitted, null-free | 18382.0 | 15544.8 | *-15.4%* |
| *control:* `date_add` emitted, mixed nulls | 14216.5 | 13451.9 | *-5.4%* |
| *control:* `datediff` emitted, null-free | 7175.6 | 7190.6 | *+0.2%* |
| *control:* `datediff` emitted, mixed nulls | 9531.4 | 8955.0 | *-6.0%* |

The control moved by as much as the treatment - identical bytecode, -15.4% to
+0.2% - so this harness's noise floor is around 15% and the conversion's effect
is not distinguishable from it. **The conversion is restored**, in shape 3, on
"no measurable effect", not on a win. Shapes 1 and 2 stay rejected: those losses
were large enough to clear even the degraded harness's noise, and the reasons
are structural rather than statistical, which is why both are written into
`DateVectorOps`'s class doc where the next person will meet them.

**The debt this leaves.** `DateVectorOpsBenchmark`'s numbers are measured in a
JVM the JUnit suites have already warmed and polluted. Every committed figure in
`DateVectorOpsBenchmark-jdk25-results.txt` inherits that, and milestone 4's
VARKA-25 is about to ask this harness questions it cannot answer. Recorded in
`m4/PLAN.md`'s debt register rather than fixed here: the fix (a forked
JMH JVM, or separating the JMH phase from the test phase) moves every committed
number in that file, which is its own task's worth of work.

## 7. Compaction: `compress(mask)` and the two batch-level fast paths

### 7.1 Where the kernel had to live, and what that cost

`varka-engine` is a **test**-scope dependency of both catalyst and `sql/core`.
That is deliberate and load-bearing: production code never links the engine, and
emitted bytecode reaches `VarkaVectorSupport` by name through the context class
loader. A compaction kernel is different from every other Varka vector loop -
Scala calls it directly on the batch path - so it has to be on the compile
classpath, and neither module had one.

Settled with the owner: **enable the incubating Vector API on catalyst's own
main compile path** and put `SelectionVectorOps` beside `VarkaSelectionBitmap`.
Two details were not visible when the choice was made and are recorded here
because they shape the diff:

* Catalyst's Java is compiled by `scala-maven-plugin`, not
  `maven-compiler-plugin` - the root pom sets `skipMain` and `skip` on the
  latter - so the flag goes in that plugin's `javacArgs` with
  `combine.children="append"` to keep the parent's release flag. sbt takes a
  module's dependencies from its pom but not its compiler arguments, so
  `SparkBuild.scala` repeats it (`VarkaCatalystVector`), exactly as
  `VarkaEngine` already does for the engine module.
* The kernel cannot call the engine's bitmap helpers, so it carries private
  copies of that arithmetic - about 35 lines. `VarkaSelectionBitmap` in the same
  package already keeps a read-side copy for the same reason, and the layout is
  Arrow's and fixed, so the duplication is bounded and documented rather than
  open-ended.

### 7.2 The kernel

One lane group at a time: `IntVector.compress(mask)` moves the selected lanes to
the low end, `Long.compress` does the same for the validity bits. Two decisions
carry it:

* **Every data store is unmasked.** A masked store costs 2.3x-2.9x and this
  would pay one per lane group. The caller allocates the destination with one
  whole lane group of slack past the selected count, so a store at the running
  output position can always write a full vector; `setValueCount(count)` makes
  the slack invisible.
* **A group that selects nothing costs one bitmap read and a branch**, which is
  what keeps low selectivity cheap.

Applied at `getTypeWidth() == 4` - a width check, not a type check, so a future
Arrow type of the right width is served by a bit-for-bit lane move. Width 8
waits for VARKA-29; everything else keeps the per-row typed copy.

### 7.3 What it is worth

Isolated by an in-session A/B - the same JVM session, `compress` on and off,
with the two batch-level fast paths present in both arms, after the day's
lesson that cross-run comparison on this machine is worth nothing:

| rung | scalar copy | `compress` | gain | ladder |
|---|---|---|---|---|
| 0% selected | 6.9 ns/row | 7.6 | -9% | 2.5x -> 2.2x |
| 1% selected | 5.8 | 5.4 | +7% | 2.2x -> 2.5x |
| 15% selected | 5.4 | 4.1 | +32% | 2.5x -> 3.4x |
| 50% selected | 7.2 | 4.0 | +80% | 2.4x -> 4.2x |
| 85% selected | 8.4 | 4.0 | +110% | 2.4x -> 5.0x |
| 100% selected | 8.6 | 3.8 | +126% | 2.4x -> 5.5x |

The 0% rung's -9% is the noise floor showing itself: at zero selected rows the
compaction is skipped entirely in both arms, so that row is measuring nothing
and usefully calibrates the rest.

The shape of the win is the interesting part. The scalar copy's cost grew with
the number of selected rows - 5.4 ns/row at 15% to 8.6 at 100% - because it is
one Arrow call per selected row. `compress` is flat at 3.8-4.1 across the whole
ladder, because it is one instruction per lane group whatever the group holds.
**Compaction has stopped being a function of selectivity.**

### 7.4 The two batch-level fast paths

`count == 0` skips the per-row scans, which were O(len) whatever they found.
`count == len` forwards the child's columns instead of copying them - the
earlier rule that a compacting filter owns every output column holds only where
the compaction is real.

**The forwarding path is unmeasured, and the reason is worth writing down.**
`VarkaFilterBenchmark`'s data carries nulls, and a null `d` makes `d < DATE`
unknown rather than true, so *no rung of the ladder selects literally every
row* - the "100% selected" rung selects every non-null row and still compacts.
That is confirmed rather than assumed: if forwarding had fired there, the two
A/B arms above would have been identical at that rung, and they differ by 126%.
So the path ships on construction (it removes a copy) plus a test that asserts
the output column is the *same object* as the input's and that releasing the
output leaves it readable - the ownership hazard being the only way this can be
wrong. Measuring it needs a null-free rung on the ladder, which is a small,
well-defined follow-up rather than something to improvise here.

## 8. The predictions, scored

Registered in section 3 before any measurement. The project keeps count, so
these are scored as they came out, not as they read best.

1. **"The tail's runtime share is ~0 at aligned batch sizes and under ~2-4% at
   unaligned ones; the case is emitter surface, not speed."** *Met on the claim
   that mattered, wrong on the number.* Aligned: exactly zero, as predicted.
   Unaligned: ~11% of a 4096-row batch's **kernel** time, not 2-4% - the guess
   used the wrong denominator - but 0.02-0.1% of end-to-end query time, which is
   the denominator the claim rested on. The case was made on emitter surface and
   that is what it should have been made on.
2. **"Emitted class size within +-20%, no shape past +50%."** *Met with room
   to spare:* -2.2% to +2.2% over seven shapes.
3. **"`compress` recovers 1-2 ns/row; the compacting rung moves from 2.7x to at
   most ~3.2x."** *Wrong, and badly pessimistic.* It recovers up to 4.8 ns/row
   and the rung reaches 5.5x. The error was inherited rather than invented:
   VARKA-21 priced the typed scalar copy at "~1-3 ns/row" from the 100% rung
   alone, and that figure was read here as a ceiling on what any faster copy
   could recover. It was not a ceiling - the scalar copy's cost *grows with
   selected rows* (5.4 ns/row at 15%, 8.6 at 100%) while `compress` is flat, so
   the recoverable amount is larger than the single-rung reading suggested.
   **The lesson, and it generalises past this task: a cost quoted at one point
   of a ladder is not a bound on the whole ladder.**
4. **"The `count == len` forwarding path is worth more than `compress`."**
   *Unscored, and on the available evidence wrong.* The ladder cannot reach the
   forwarding path at all (section 7.4), and `compress` alone doubles the
   compacting path at high selectivity.
5. **"No engine JMH number moves."** *Unscoreable, which is itself the finding.*
   The numbers moved a great deal, and then turned out to be measured in a
   degraded JIT state (section 6). The clean harness finds no effect. The
   prediction's premise - that this harness answers questions like this - was
   the thing that failed.

## 9. Declined: the per-lane-group `anyTrue`/`allTrue` fast paths

The milestone's third deliverable for this task was "per-lane-group all-null and
all-valid fast paths, where the prologue today has them only per batch". The
owner scoped it during planning as measure-then-ship-or-decline, with the
batch-level pair (section 7.4) taking priority. **It is declined, with the
task-16 kind of reason: the evidence against it was produced twice by this task
while it was doing something else.**

The change would put a branch on each referenced input's validity word inside
the lane-group body, to skip a handful of long-word operations when a group is
entirely valid or entirely null. Two independent measurements this task already
made say that adding anything to that body is expensive out of proportion to
what it removes:

* Sharing a mask helper between `DateVectorOps`'s loop and its epilogue cost
  24-50%, because the helper carried a byte loop into what the hot loop had to
  inline.
* Leaving the epilogue inline in the kernel method cost 8-40%, *in proportion to
  how many Vector API calls it added*, on a branch that ran at most once per
  batch and in the benchmarked sizes never ran at all.

The proposed fast path is the same trade with a worse ratio: a branch per lane
group - taken thousands of times per batch, not once - to save a few `land`/`lor`
instructions on a long word that is already in a register. The per-batch dense
and masked dispatch the driver already does is where this idea pays, and it is
already there.

Two conditions would reopen it, and both are real. If VARKA-29's int64 lanes
halve the group size, the per-group validity work doubles relative to the
compute. And if VARKA-31's instruction-level assertions show the masked body's
word arithmetic is not folding the way it is assumed to, the premise changes.
Neither is a reason to guess now.

## 10. Outcome

Status: **DONE.** Four commits, each green on its own:

1. The measurement (section 2), before any emitter change.
2. The masked epilogue: `VarkaLoopEmitter` from 2110 lines to 1914, one of its
   five per-node switches over the sealed IR hierarchy gone, both pinned shape
   hashes and the pinned line map unchanged - the behaviour-preservation proof
   the milestone plan expected to have to regenerate instead.
3. The hand-written kernels, on "no measurable effect" and a harness finding.
4. `compress(mask)` compaction and the two batch-level fast paths: the
   compacting filter ladder from 2.4-2.5x to 2.5x-5.4x, and compaction's cost no
   longer a function of selectivity.

All six Varka benchmark files regenerated in one run on an idle machine and the
docs requoted from it. Green at both vector widths: catalyst 82/82, sql/core
123/123, the engine module at both widths, `catalyst/doc`, `dev/lint-java` and
scalastyle.

What this task added to the milestone beyond its own row: **VARKA-31**, asserting
the instructions rather than the ratio, scheduled before VARKA-25 because it is
VARKA-25's instrument; and the milestone's **debt register**, opened with the JMH
harness that cannot currently answer the questions VARKA-25 is about to ask.

## 11. The Maven-only failure the build change caused, and why SBT could not see it

PR #56's first CI run was green everywhere except **Java 25 build with Maven**,
which failed compiling catalyst with
`NoClassDefFoundError: jdk/incubator/vector/VectorSpecies`. Every local gate had
passed, including `dev/lint-java`, and so had the aarch64 and x86_64 engine jobs.

The cause is not javac. Section 7.1's `javacArgs` block compiles
`SelectionVectorOps` correctly; the failure happens *after* a successful compile,
in zinc's API extraction. `sbt.internal.inc.ClassToAPI.structure` calls
`Class.getDeclaredFields()` on the class file it has just written, which resolves
the field types **reflectively, in the compiler's own JVM** - and that JVM had no
`--add-modules jdk.incubator.vector`. So the flag is needed twice: in
`scala-maven-plugin`'s `javacArgs`, which compiles the source, and in the same
plugin's `jvmArgs`, which is what the compiler itself runs with.

**SBT cannot see this, structurally.** The sbt launcher already runs with
`--add-modules=jdk.incubator.vector` (Spark's own `javaOptions`, for
`dev.ludovic.netlib.blas`), so zinc-under-sbt resolves the module and the same
code compiles and analyses cleanly. This is the second time in two tasks that the
Varka Java surface has produced an SBT-green, Maven-red failure - VARKA-23's was
Guava relocation under shading - and both times the only gate that saw it was the
Maven CI job. Recorded in `SKILLS.md` beside the shading entry and in
`sql/varka/AGENTS.md`'s house rules, with the seconds-long local check that
reproduces it: call `getDeclaredFields()` on the compiled class from a plain
`java -cp`, once with the flag and once without.

The lesson worth carrying past this instance: **a local build being green is not
evidence about Maven when the difference between them is a JVM flag one of them
sets for its own reasons.** VARKA-23's entry said the same thing about a shading
rewrite. Anything that puts a new *kind* of type into a Varka Java signature -
incubator, class-file API, shaded - should be checked against Maven before the
push, not after.

## 12. Explicitly out of VARKA-24

* **Width-8 compaction** - `LongVector.compress` waits for VARKA-29's lane type;
  the width-4 check routes everything else to the per-row typed copy unchanged.
* **A null-free rung on the filter ladder**, which is what it would take to
  measure the `count == len` forwarding path (section 7.4).
* **Two `compactInts` micro-optimizations the review named, both
  measurement-gated**: reading a whole aligned group's selection bits in one
  access instead of `bitsAt`'s byte walk, and replacing the null-free path's
  per-group validity read-modify-write with one bulk fill of the first `count`
  bits after the loop. The committed numbers are flat at 3.8-4.2 ns/row already,
  so neither enters without an A/B showing it pays - the same bar everything
  else in this task cleared or fell to.
* **The per-lane-group fast paths** - declined above, with the two conditions
  that would reopen them.
* **Fixing the engine's JMH harness** - the debt register entry, not this task:
  the fix moves every number in that results file.
* **Unrolling** (VARKA-25), which shares this task's alignment harness but none
  of its commits, and **anything that adds an IR node, expression, type or lane
  width** - the pinned hashes holding is how that boundary was checked.
