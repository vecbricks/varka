# Task 32 (replanned): one decomposition, several fields

Supersedes the plan carried inline in `PLAN_MILESTONE_4.md` section 2.9 and the
"declined" outcome recorded there. The first pass answered the ceiling question
with a measurement that does not measure what it claims to; this plan repairs
the measurement first and then, if it clears, builds the sharing.

## 1. Why the first pass is being redone

Section 2.9's gate was the right gate: build the ceiling before the mechanism,
and decline the task if the ceiling is close to the 441.2 M rows/s four
independently emitted nodes reached in the parity file as it then stood. The
gate ran, reported 225.8, and the task was declined.

The kernel that produced 225.8 does not have the shape it claims. It is written
as

```
Fields f = computeFields(days);   // record of four IntVectors
```

and `computeFields` compiles to **376 bytes of bytecode** (`javap -c -p` on the
committed class; `-XX:+PrintInlining` says the same thing at runtime). C2's
`FreqInlineSize` is 325, so `computeFields` never inlines into the loop. Once it
does not inline, the `Fields` record and the four `IntVector`s it holds cannot be
scalar-replaced: escape analysis is a per-compilation-unit argument and there is
no unit that contains both the allocation and its consumers. So the kernel
really allocates five objects per lane group and really moves vectors through
the heap - and three of `computeFields`'s six calls to the 12-byte `magic()`
helper stop inlining too, once `computeFields` is itself over budget.

`VarkaLoopEmitter.emitChrono` - the path the kernel exists to model - emits
**zero** call boundaries in the lane path. Every intermediate is a local, every
op is a `jdk.incubator.vector` intrinsic in one method. The kernel and the
emitter therefore differ in the one dimension that dominates Vector API
throughput, and the 1.4-1.9x "slowdown" is the difference between those two
shapes, not between sharing and recomputing.

Two further reasons the number is not comparable, both from the same review:

* the shared kernel emits **no range guard**, while the four-node baseline emits
  one per field - so the baseline is charged four guards the ceiling does not
  pay, which flatters the *baseline*; and
* the shared kernel ORs validity into **one** buffer per lane group where a
  shippable version writes four physical Arrow validity buffers - which flatters
  the *ceiling*.

They push in opposite directions and neither is large, but a ceiling measurement
has to charge both sides the same things.

**What the arithmetic says the answer should be.** `year` alone runs at 1816.6
M rows/s and the four-field projection at 445.7 - a ratio of 4.08, i.e. nothing
is shared today beyond the column load and the loop control.

**One op count, used everywhere.** A calendar field is ~50 vector ops, of which
the shared prefix through `marchMonth` (the guard included) is **~45** and the
field's own tail is **~5**. So four fields cost **~200 ops** as four independent
nodes and **~65** shared - a saving of **~135 ops**, which is the figure
`SKILLS.md` and `PLAN_MILESTONE_4.md` section 2.9 both quote. (The review of
PR #66 read those two as contradicting each other, "~45 shared ops" against
"~135 ops the sharing saved"; they do not - one is per field, the other is the
three redundant copies a four-field projection drops. Both now say which.)

If throughput tracked op count the way task 26 found it does for a single-output
loop, the shared shape would land near 1817 x 50/65 ~ 1400 M rows/s, i.e. **3x
the four-node number** - not 0.5x. The measured 225.8 is a factor of six away
from that, which is about what a per-lane-group heap allocation costs.

Register pressure is also the wrong worry at these widths. Five int vectors and
two masks stay live across the tails; AVX-512 has 32 zmm registers and 8 mask
registers, 128-bit SSE has 16 xmm. Task 17's contrary result (raising
`GROUP_BUDGET` so two outputs kept cross-output CSE *lost*, 4119.9 against
2928.2) was a different trade: there the shared chain was eight ops, so
recomputing it was nearly free and the wider method was pure cost. Here the
shared work is ~45 ops and the tails are ~5. The ratio that decides the direction
is shared-work to per-field-tail, and it is 9:1 here against roughly 1:1 there.
Whether that argument survives contact with a narrow vector register file is a
different question, and section 7 is where it gets answered.

## 2. Decisions

1. **The task is not declined on the current evidence.** The measurement is
   repaired first, and the gate is then re-run honestly. If the repaired ceiling
   still does not clear the four-node number, the task declines with a real
   reason.
2. **The mechanism is emitter-side fragment sharing, not a multi-value IR node.**
   Section 2.9 listed a multi-value node as "the general answer". It is not
   needed: the values worth sharing are locals inside one node's emitted
   bytecode, and the emitter can share locals without the IR ever naming them.
   Both mechanisms emit *identical bytes*; they differ only in where the decision
   lives. That is an engineering-cost choice, not a measurable one, which is why
   this is the one place in this plan that is settled by argument rather than by
   building both - there is nothing to measure between them. A multi-value node
   returns only if some future primitive needs the shared value visible to the
   *planner* (to feed further IR nodes, or to CSE across loop-method groups);
   that stays in the debt register.
3. **Both lowerings ship, behind an emit option**, the way task 26 shipped
   `TOTAL`/`NARROWED` and task 14 shipped the three `FloorMod7` variants. The
   loser stays a live, differentially tested reference variant rather than dead
   code, and the parity benchmark keeps both cases so a future retune is measured
   rather than argued.
4. **Mechanism 3 stays declined**, with section 2.9's reason unchanged:
   decomposing the calendar into primitive IR nodes would put a four-field
   projection at ~60 nodes against `MAX_FUSED_NODES = 64`, and give the IR a
   general arithmetic vocabulary to serve one family.

## 3. The fragment mechanism

### 3.1 What a fragment is

A **fragment** is a run of emitted lane ops that (a) several nodes need, (b)
depends only on one shared child node, and (c) leaves its results in scratch
locals rather than on the operand stack. It is the sub-node counterpart of the
CSE the emitter already does between nodes.

```java
private record FragmentKey(FragmentKind kind, VarkaVectorIR child) {}
private enum FragmentKind { CHRONO_PREFIX }
```

One kind for now. The key carries the kind so that a second one is additive
rather than a rewrite.

`CHRONO_PREFIX` is exactly what `emitChronoPrefix` already emits (task 40 -
PR #67 - factored it out of `emitChrono`; this plan depends on that factoring
and so is sequenced after it): the guard, the biased day, era, day of era,
century, year of century, day of year, and the March-based month, into the eight
slots `s.chronoTmp` already allocates. Nothing in the tails writes back into
those slots, which is what makes the run shareable as-is.

The guard is inside the prefix (`emitEra`) and depends only on `days`, the node's
validity word - which `planWordRef` aliases to the child's - and the epilogue
bounds mask. All three are identical for every chrono node over the same child,
so sharing the prefix shares the guard **correctly**, and drops three of the four
guards a four-field projection pays today.

### 3.2 The four edits

* **`Slots.chronoTmp` is keyed by `FragmentKey`, not by node.** In `planSlots`,
  where the topo walk today does
  `if (isChrono(node)) s.chronoTmp.put(node, new int[]{...})`, it instead does
  `s.chronoTmp.computeIfAbsent(new FragmentKey(CHRONO_PREFIX, chronoChild(node)), ...)`.
  A small `chronoChild(VarkaVectorIR)` switch returns `n.days()` for each chrono
  node, beside the existing `isChrono`. Nodes that need extra private scratch
  (task 40's `AddMonths` wants three more) keep allocating that per node - the
  fragment owns the shared slots, the node owns its own.
* **`emitValue`'s chrono arm consults a per-lane-group fragment set.**
  `emitLaneGroup` already threads a `Set<VarkaVectorIR> computed` for node-level
  CSE; it gains a `Set<FragmentKey> emittedFragments` beside it, reset per lane
  group for exactly the same reason. `emitChrono` becomes: if the key is already
  in the set, skip straight to the tail; otherwise emit the child, `astore` it,
  emit the prefix, add the key, then the tail.
* **Grouping counts the fragment once.** `addOps` walks a subtree adding nodes to
  a `seen` set and counting only what is new. It gains the fragment as a
  synthetic member of that set: a chrono node contributes `CHRONO_PREFIX_WEIGHT`
  (~44) the first time its fragment key is new to the group and
  `chronoTailWeight(node)` (~6) always. Because `groupOutputs` already counts
  only what is new to a group, this makes chrono siblings over one child naturally
  want to sit together - no new grouping pass, just an honest cost function.
* **The budget rule admits a cheap output into an already-wide group.** Today
  `groupOutputs` splits when `ops + marginal > budget`. With the fragment
  counted, a four-field projection is `50, 6, 6, 6`: the first output already
  exceeds the budget on its own (which the current rule explicitly allows), and
  every rule-following split after that *duplicates a 44-op prefix to save 6 ops
  of method width* - strictly more work in strictly more methods. The rule
  becomes:

      join when  ops + marginal <= groupBudget                  (today's rule)
            or   marginal <= groupBudget && ops + marginal <= FUSED_CEILING

  The second clause admits only an output that is cheap *because it reuses work
  the group already has*, and `FUSED_CEILING` keeps the method away from the
  compile cliff `GROUP_BUDGET` exists to avoid and from the 8000-byte
  `HugeMethodLimit` that tasks 43/44 are about. `FUSED_CEILING` is **set by the
  measurement in section 5.2, not chosen here**; 96 is the starting candidate
  (one prefix plus eight tails).

### 3.3 The emit option

`VarkaEmitOptions` gains `boolean shareChronoPrefix` beside `cse`, with
`withShareChronoPrefix`, a `canonical()` rendering, and inclusion in the shape
hash on the existing non-default-only rule. `shareChronoPrefix = false` reproduces
today's bytes exactly, which is what makes the differential test in section 5.1
meaningful and what lets the benchmark price the change as an A/B in one run.

Which value `DEFAULTS` carries is the deliverable of section 5.2.

### 3.4 What this does for the rest of the calendar family

Tasks 33, 34 and 40 all start from the same prefix (`next_day`, `dayofyear`,
`add_months`, `date +/- INTERVAL n MONTH/YEAR`). Once the fragment is keyed on
(kind, child) rather than on a node type, `SELECT year(d), dayofyear(d)` and
`SELECT month(d), add_months(d, 1)` share it without another line of code. That
generality is the argument for doing this in the emitter rather than special-
casing the four task-26 fields.

It also cuts the epilogue. `epilogueMasked` is one method over *every* output by
task 24's deliberate decision, and the debt register measures it at 7530 bytes
for 16 calendar outputs and 8079 for 17 - across `HugeMethodLimit`, past which
HotSpot compiles nothing at all. Sixteen calendar outputs over one date column
share one prefix under this change, so the epilogue shrinks by roughly the 15
prefixes it stops repeating. That does not close tasks 43/44 - a projection over
16 *different* date columns still crosses - but it moves the reachable cases well
back from the edge, and section 5.3 measures the new byte counts so tasks 43/44
plan against real numbers.

## 4. Files

**Step A - repair the measurement** (branch `varka-task-32`, PR #66):

* `sql/varka/engine/.../vector/ChronoVectorOps.java` - hand-inline
  `computeFields` and `fourFieldsEpilogue`'s copy of it into their loops, so the
  lane path has no call boundary, matching what `emitChrono` emits; drop the
  `Fields` record; add the range guard (two compares, OR, AND with validity, OR
  into an accumulator, `anyTrue` after the loop) so the ceiling pays what a
  shippable version pays; write four destination validity buffers rather than
  one. Requote the class javadoc's baseline number from the committed results
  file rather than from memory.
* `sql/varka/engine/.../vector/ChronoVectorOpsTest.java` - replace the quarter
  oracle `(expected.getMonthValue() + 2) / 3`, which restates the implementation,
  with one derived from `LocalDate` independently of the month formula; drop the
  private `isBitSet` copy in favour of `VarkaVectorSupport.isBitSet`; restore
  `100` to `SIZES` to match `DateVectorOpsTest`.
* `sql/catalyst/src/test/scala/.../VarkaEmitterParityBenchmark.scala` -
  generalize the `chunked` helper to take a per-chunk callback instead of the
  hand-copied loop the new case added.
* `sql/catalyst/benchmarks/VarkaEmitterParityBenchmark-jdk25-results.txt` -
  regenerated in the same commit as the benchmark change, as every prior commit
  touching it did.
* `SKILLS.md` - the task-17 figures reintroduced stale (4587/3196) go back to the
  committed parity file's current 4119.9/2928.2 - which this task's own
  regeneration moves again, so both are requoted from the regenerated file, and
  `VarkaLoopEmitter`'s `GROUP_BUDGET` javadoc with them; the "ops saved" figure
  is reconciled with
  `PLAN_MILESTONE_4.md`'s (one number, quoted once, referenced from the other).
* `PLAN_MILESTONE_4.md` - section 2.9's outcome and section 9's debt entry
  rewritten around the repaired number; "sweep" reserved for `VarkaChronoSuite`'s
  exhaustive opt-in check, with `ChronoVectorOpsTest`'s ~100,000 sampled values
  described as sampling.
* `PLAN_TASK_34.md`, `PLAN_TASK_35.md` - **left alone.** The review of PR #66
  flagged their "task 32 may move the plumbing; it will not move the arithmetic"
  paragraph as a stale contingency this task had resolved. Under this plan it is
  not stale: it is exactly what step B does, and it is exactly the instruction
  those two recipes need - write the tail, do not restructure `emitChrono`, do
  not try to share anything by hand. The finding is recorded as not actioned,
  with this reason.

**Step B - build the sharing** (new branch off master, after PR #67 lands):

* `VarkaLoopEmitter.java` - `FragmentKey`/`FragmentKind`, `chronoChild`,
  `chronoTailWeight`, `CHRONO_PREFIX_WEIGHT`, `FUSED_CEILING`; `planSlots`
  keying `chronoTmp` by fragment; `emitLaneGroup`'s `emittedFragments` set;
  `emitChrono` skipping an already-emitted prefix; `addOps`/`groupOutputs` per
  section 3.2. `CHRONO_WEIGHT`'s javadoc is rewritten - it stops being "what a
  calendar node weighs" and becomes prefix plus tail.
* `VarkaEmitOptions.java` - the option, its `with`, `canonical()`, `isDefault`.
* `VarkaLoopEmitterSuite.scala` - the differential in section 5.1; the pinned
  line map and `everyNode` fixture, which move; a grouping test asserting the
  method partition for one, two, three and four fields over one column and over
  two different columns.
* `VarkaShapeCacheSuite.scala` - the `everyNode` hash, which moves.
* `VarkaDifferentialSuite.scala` - a query-level case per section 5.1.
* `VarkaEmitterParityBenchmark.scala` + results - section 5.2's cases.
* `docs/sql-varka.md`, `README.md` - requoted from the one regeneration run, if
  the default changes.

## 5. Verification

Both vector widths everywhere, per the standing gate:

```
build/sbt catalyst/Test/compile sql/Test/compile
build/sbt 'catalyst/testOnly *Varka*' 'sql/testOnly *Varka*'
build/sbt "project catalyst" 'set Test/javaOptions += "-XX:MaxVectorSize=16"' 'testOnly *Varka*'
build/sbt "project sql"      'set Test/javaOptions += "-XX:MaxVectorSize=16"' 'testOnly *Varka*'
./build/mvn -f sql/varka/engine/pom.xml install
dev/lint-java && dev/scalastyle
```

(`JAVA_OPTS` does not reach the forked test JVM; the `set Test/javaOptions` form
is the one that actually narrows the vectors.)

### 5.1 Correctness

The two lowerings must agree bit for bit. `shareChronoPrefix` is an optimization
and never a semantics change, which is exactly the property `cse` is already
pinned on:

* every shape the emitter suite drives, emitted both ways, outputs compared
  lane for lane, at both widths;
* the guard's behaviour under sharing: a batch with one out-of-range row must
  decline under both settings, and an in-range batch with nulls must decline
  under neither - the guard is now emitted once for several outputs, so the
  "silent total loss of fusion" failure mode `emitEra`'s javadoc describes has a
  new way to appear and needs its own case;
* `SELECT year(d), month(d), dayofmonth(d), quarter(d)` against the row engine
  over the Gregorian sweep data, both settings.

### 5.2 The measurement that decides the default

In `VarkaEmitterParityBenchmark`'s existing "year" section, same 4096-row chunks,
same repeat count, same in-range null-free data, on an idle machine, five
iterations over two-second windows, any ratio under 1.3x re-checked by minimums:

| case | shared | per-output |
|---|---|---|
| `year` | - | 1816.6 (committed) |
| `year, month` | new | new |
| `year, month, dayofmonth` | new | new |
| `year, month, dayofmonth, quarter` | new | 445.7 (committed) |
| four fields, mixed nulls | new | new |
| `year(d1), year(d2)` (two columns, nothing to share) | new | new |

The last row is the regression guard: two chrono nodes over *different* children
must not be pushed into one method by the new budget clause.

Step A's repaired hand-written kernel is measured in the same run as an
independent check that the emitted shared path reaches the hand-written ceiling.

### 5.3 The compile cliff, measured rather than assumed

`GROUP_BUDGET = 16` exists because a 64-op multi-output loop took a ~10 s tier-4
compile, during which the loop ran C1 with boxed vectors at ~1% speed. A shared
four-field loop is ~65 ops across four outputs - squarely the shape that finding
was made on - so this cannot be waved past on the grounds that a 59-op
*single*-output loop measured healthy.

Per the project's standing rule that JIT facts come from the JVM's own output:

* `-XX:+PrintCompilation` on the four-field kernel under both settings, reporting
  the wall time between the tier-4 task being queued and its completion for each
  loop method - one ~65-op method against four ~50-op methods, which may also
  compile in parallel on separate compiler threads;
* `-XX:+PrintInlining` confirming no call boundary survives in the lane path of
  either the emitted kernel or the repaired hand-written one;
* emitted bytecode size per loop method and for `epilogueMasked`, at 1, 2, 4 and
  16 calendar outputs over one column, both settings - the numbers tasks 43 and
  44 will plan against.

`FUSED_CEILING` is set from these numbers. If the compile time turns out to be
the binding constraint rather than throughput, the honest outcome is a ceiling
low enough to share two or three fields but not sixteen, which is still most of
the win - not a decline.

## 6. Predictions, registered before the measurements

Scored honestly in section 7 when the numbers land, per `sql/varka/AGENTS.md`.

1. The repaired hand-written kernel lands between 900 and 1400 M rows/s at
   AVX-512, i.e. **2.0x to 3.2x** the four-node baseline - reversing the recorded
   1.9x loss. Confidence: high, on the ~3x op-count ratio and the fact that the
   defect being removed is a per-lane-group heap allocation.
2. The emitted shared path lands within 10% of the repaired hand-written kernel.
   Confidence: medium-high - the emitter has no call boundary to begin with, so
   the two should be the same bytes modulo the driver.
3. Two fields shared beat two fields separate by 1.6x-1.9x; three by 2.2x-2.7x.
   The gain is sublinear in field count because the tails and the four stores are
   not shared.
4. Compile time for the ~65-op four-output method is under 1 s at tier 4 - the
   task-11 cliff was 64 *distinct nodes across many outputs* in a loop whose op
   mix was different, and a single-output 59-op loop already compiles promptly.
   Confidence: **low**. This is the prediction most likely to be wrong, and the
   one with a real chance of capping the mechanism at two or three fields.
5. At 128-bit the ratio is smaller than at AVX-512 but still above 1, because
   fewer lanes per op make the per-op cost relatively larger while the op-count
   saving is unchanged.
6. `epilogueMasked` at 16 calendar outputs over one column drops from 8079 bytes
   to under 3000, moving it back across `HugeMethodLimit` - a side effect, not a
   closure of tasks 43/44.

## 7. Outcome of step A: the gate clears at AVX-512, and is a wash at 128-bit

The kernel was rewritten with the whole lane path written out by hand (no method
call of any kind), with the narrow-range guard, and writing four destination
validity buffers through a `VarkaFusedKernel`-shaped array ABI. The benchmark's
hand-copied chunk loop was replaced by the same `eachChunk` walk every other case
in the section uses, so the two arms cannot differ in their addressing.

**The lane path is clean, confirmed from the JVM's own output** rather than
inferred. `javap -c -p` puts `vectorFourFields` at 936 bytes and
`fourFieldsEpilogue` at 642, with **zero** `invokestatic` to any `ChronoVectorOps`
method. `-XX:+PrintInlining`, restricted to this method by
`-XX:CompileCommand=option,...::vectorFourFields,PrintInlining`, reports exactly
one callee not inlined:

```
147516 4349    3  ChronoVectorOps::vectorFourFields (936 bytes)
     @ 919 ChronoVectorOps::fourFieldsEpilogue (642 bytes)
           failed to inline: callee is too large
147547 4349    3  ChronoVectorOps::vectorFourFields (936 bytes)
     COMPILE SKIPPED: out of virtual registers in linear scan
147569 4357 %  4  ChronoVectorOps::vectorFourFields @ 233 (936 bytes)
```

Bytecode 919 is past the loop - the epilogue runs once per batch, not once per
lane group - and everything inside the loop is a force-inlined vector intrinsic.
The old `computeFields` boundary is gone.

The third line is a finding in its own right: **C1 refuses this method outright**,
"out of virtual registers in linear scan", so it runs interpreted until C2's
tier-4 compile lands. It reproduces on every run. It does not affect a
steady-state benchmark number, and it is not a defect in this kernel - a 936-byte
straight-line vector body is what the emitter produces too - but it is the first
concrete evidence in this project that a wide shared body has a warmup cost the
four narrow bodies do not, and step B's compile-time gate (section 5.3) inherits
it as a thing to measure rather than a thing to assume.

### The numbers

AVX-512 (`IntVector.SPECIES_PREFERRED`, the development machine's native width),
`VarkaEmitterParityBenchmark`'s "year" section, 4096-row chunks, five iterations
over two-second windows, idle machine:

| | run 1 | run 2 | run 3 | run 4 (the committed file) |
|---|---|---|---|---|
| four separate emitted nodes | 450.4 | 448.8 | 435.1 | 445.7 |
| shared decomposition, hand-written | **692.4** | **678.8** | **661.7** | **679.0** |
| ratio | **1.54x** | **1.51x** | **1.52x** | **1.52x** |

This is a record of four runs; the committed results file is run 4, and it is
the one every other document quotes. `year` alone measured 1791.2, 1822.2, 1797.2
and 1816.6 in the same runs, so the four-node case is still about 4x one field:
nothing is shared today.

128-bit (`-XX:MaxVectorSize=16`), five runs, because the ratio came in under the
project's 1.3x re-check threshold and the first pass was faulted for leaving
exactly this number unrepeated:

| run | four nodes | shared | ratio |
|---|---|---|---|
| 1 | 155.9 | 165.7 | 1.06x |
| 2 | 157.6 | **236.1** | **1.50x** |
| 3 | 154.1 | 165.6 | 1.07x |
| 4 | 156.4 | 165.9 | 1.06x |
| 5 | 157.4 | 167.0 | 1.06x |

Compared by minimums, as the rule requires: 121, 85, 121, 121 and 120 ms against
a four-node baseline of 128, 127, 130, 128 and 127 ms - stable to 2% on the
baseline and bimodal on the shared kernel. Within a run the shared kernel's stdev
is 0 ms; between runs it moves 42%. That is a compilation the JVM either finds or
does not, not measurement noise, and averaging the two modes would describe a
state no run is ever in. `-XX:+PrintCompilation` on runs 3, 4 and 5 shows an
identical event sequence for all three slow-mode runs, so the C1 skip above is
not the discriminator; run 2 was not instrumented and the fast mode has not been
reproduced since.

**So: the gate clears at AVX-512 and does not clear at 128-bit.** Sharing is
worth 1.5x where there are 32 vector registers and 8 dedicated mask registers to
hold five live intermediates plus four outputs, and worth nothing reliable where
there are 16 vector registers and masks must live in them too. That is task 17's
register-pressure effect, found again - but as a width-dependent ceiling on the
win rather than as a reversal of its sign.

### Predictions, scored

1. **Wrong.** Predicted 900-1400 M rows/s at AVX-512 (2.0x-3.2x); measured 661.7
   to 692.4 over four runs (1.51x-1.54x). The direction was right and the confidence was stated
   as high, so this is a real miss on magnitude: the op-count model
   (`1817 x 50/65 ~ 1400`) assumes throughput is proportional to vector ops and
   nothing else, and it over-predicts by a factor of two. Time not accounted for
   by the decomposition - four stores, four validity-bitmap read-modify-writes,
   the chunk prologue and the loop control - is roughly half the four-node case's
   cost, and sharing does not touch any of it. **An op-count ratio is an upper
   bound on a sharing win, not an estimate of one**, and the next such prediction
   should be made as a bound.
2. Not yet measurable - step B builds the emitted path.
3. Not yet measurable - step B adds the two- and three-field cases.
4. Not yet measurable. Partial evidence above: C1 cannot allocate registers for
   the 936-byte body at all, which is not what prediction 4 was about (C2
   tier-4 time) but is not encouraging for it either.
5. **Right, and for the wrong reason.** Predicted the 128-bit ratio would be
   smaller than AVX-512's but still above 1: it is, 1.06x against 1.51x. The
   reason given - "fewer lanes per op make the per-op cost relatively larger
   while the op-count saving is unchanged" - does not survive the data, because
   that argument predicts a *proportionally similar* win, not its disappearance.
   The register file is the better explanation, and the bimodality is what a
   marginal allocation looks like.
6. Not yet measurable - step B.

### What does not fix the 128-bit mode, measured

Six hypotheses were tested against the bimodality, because a 1.5x win that the
narrow-vector shape cannot reach is what decides step B's scope. All six
failed, and they are recorded so nobody pays for them twice.

1. **Shorter live ranges.** `ChronoVectorOps.vectorFourFieldsShortLive` is the
   same arithmetic and the same op count on a schedule that keeps fewer values
   live: the year assembly hoisted so `era`, `century` and the year of century
   die before the tails, and each output stored the moment it exists rather than
   all four at the end - which is also what `emitLaneGroup` does, so this is the
   variant that mirrors the emitter. It is **slower at both widths**: 626.9 and
   642.6 against 686.2 and 691.3 at AVX-512, 156.5 and 157.7 against 165.6 and
   167.1 at 128-bit. Register pressure is real but is evidently not relieved by
   holding fewer values; C2's scheduler does better with the wide window. The
   variant is kept, differentially tested against the other, as the reference
   that stops this being re-proposed - and as a caution for step B, since the
   emitter's natural store-as-you-go shape is the losing one here.
2. **Forcing the two validity helpers to inline.**
   `-XX:CompileCommand=inline,...VarkaVectorSupport::orValidityBitsAt` and the
   same for `validityBitsAt`. These are the only calls left in the lane path, and
   `-XX:+PrintInlining` shows them genuinely failing, at bytecode 828, 839, 850
   and 861, with `NodeCountInliningCutoff` on one compilation and
   `callee is too large` on another - 212 bytes of a four-arm switch on
   `groupBytes(lanes)` that a constant lane count would fold away if it ever got
   in. Forcing them changes **nothing**: 691.3 at AVX-512 against 686.2 unforced,
   and at 128-bit one fast run and one slow one, the same split as without it.
   **And the reason is not that inlining does not matter - it is that the force
   never takes.** Re-run with the flag *and* `PrintInlining`, the same
   `failed to inline` lines are still there, and raising
   `-XX:LiveNodeCountInliningCutoff` to 400000 does not remove them either:
   `CompileCommand=inline` overrides the size heuristics but neither flag lifts
   the check that actually fires. The call site is also not judged hot, so the
   35-byte `MaxInlineSize` applies rather than the 325-byte `FreqInlineSize`,
   and 212 bytes is over both. The lever is therefore not a flag at all - it is
   **shrinking the callee**. `orValidityBitsAt` is a four-arm switch on
   `groupBytes(lanes)` that cannot fold because it cannot inline and cannot
   inline because it has not folded; splitting it into width-specialised methods
   of about thirty bytes, which the emitter can select at emit time since it
   knows the species, breaks that cycle. That is a change to a helper every
   Varka kernel calls, so it belongs in its own task - but it is a motivated one
   now rather than a guess.
3. **Forcing every Varka class to inline**, `-XX:CompileCommand=inline,*varka*::*`
   under `-XX:+UnlockDiagnosticVMOptions`. Also nothing: 119 ms then 83 ms, both
   modes, unchanged distribution. (Its AVX-512 companion run is discarded rather
   than quoted - it overlapped a build on the same machine and its anchor case,
   `year`, came in at 1643.6 M rows/s with a stdev of 7 ms against the 1791 to
   1831 at a stdev of 0 to 1 every clean run of this session produced. The
   no-effect-at-AVX-512 conclusion rests on experiment 2's clean 691.3 instead.)
4. **Disabling on-stack replacement**, `-XX:-UseOnStackReplacement`, on the
   theory that the mode was OSR-versus-standard compilation. Three runs, all
   slow: 121, 123, 119 ms.
5. **Raising `-XX:LoopUnrollLimit` from its default 60 to 250**, on the theory
   that the four-node arms sit under the limit and the ~65-op shared body over
   it, so only the former get unrolled. Nothing: 679.3 at AVX-512 and 164.9 at
   128-bit, both inside the existing spread. The flag is not inert - it moved
   `dayofweek`, a ~20-op body, from 2630.7 to 3445.2 M rows/s at 128-bit, a 31%
   gain - so C2 does unroll more under it. It simply does not help a body this
   wide, which is worth knowing before anyone proposes unrolling as the answer
   for a large kernel.

6. **Buffer alignment**, the one hypothesis that came with outside evidence and
   still failed. JDK-8380195, "Vector API produces bimodal performance -
   nondeterministic C2 intrinsification across JVM forks", reports this exact
   shape - roughly 2x, across identically configured JVM forks, on
   `IntVector.SPECIES_256` - and was closed **Not an Issue** in April 2026 with
   the diagnosis that the array "is not always aligned, and so sometimes one
   gets fast performance (when aligned), sometimes slow performance (when
   misaligned)". `VarkaEmitterParityBenchmark` allocates every buffer at
   **8-byte** alignment, under the 16-byte vector width at 128-bit and far under
   64 for AVX-512; chunk offsets advance by 4096 rows, i.e. 16384 bytes, so the
   base address is the only thing that varies between runs. Raising every
   allocation to 64 bytes: three 128-bit runs at 164.0, 164.6 and 165.5, **all
   slow, none fast**, and one AVX-512 run at 709.1 against a 462.3 baseline,
   ratio 1.53x - every figure inside the existing spread at both widths.
   The result refutes the hypothesis in the informative direction: if alignment
   were the cause, pinning it at 64 bytes should have pinned the *fast* mode,
   not the slow one. It also says Varka's 8-byte allocations are costing nothing
   measurable, so the change was reverted rather than committed - it would move
   no number while forcing another parity regeneration. If a future task
   regenerates that file anyway, raising the alignment is a free tidy-up.

Across all these configurations the shared kernel was measured 21 times at
128-bit and landed the fast mode 4 times, with no configuration making either
mode deterministic and none shifting the distribution enough to call from four
successes. The bimodality affects only the shared kernel: the four-node
baseline it is measured against sat between 126 and 130 ms in every one of
those runs, fast mode or slow. Whatever picks the mode is inside C2's code generation for this body
and is not reachable from any of these levers.

Two things follow. First, **a JVM flag was never going to be the answer anyway**:
Spark cannot require `-XX:CompileCommand` on a user's JVM, so a flag that helped
would have been a diagnostic pointing at a code change, not a fix. The code
change these results point at, if anything, is making `orValidityBitsAt` small
enough to inline on its own merit - it is a width-generic switch called from
kernels that know their width at emit time - and that is a change to a helper
every Varka kernel calls, so it belongs in its own task with its own
measurement, not inside task 32. Second, this is the third time in this project
that a `-XX:CompileCommand=inline` flag has moved nothing in the catalyst parity
harness; the debt register's note that the same flag moves the engine's JMH
numbers 50-190% and the catalyst numbers by under 1% is the same observation,
and the harness is simply not in a state where inlining is what is left on the
table.

### What this changes for step B

**Step B splits in two, and only the first half is unconditional.** The
throughput case rests on a four-field projection, and the corpus does not contain
one - TPC-H uses `year` alone and TPC-DS pre-materialises `d_year`/`d_moy`/
`d_dom`. So the 1.5x is real but is not, on its own, worth relaxing a grouping
policy that exists to avoid a measured ten-second compile.

**B1 - fragment sharing inside a method. No policy change, do it.** Section 3.2's
first three edits only, leaving `groupOutputs` exactly as it is. Today each
calendar output already forms its own loop method, so no loop method holds two
chrono nodes and nothing there changes. What does change is the **epilogue**,
which by task 24's deliberate decision is one method over *every* output: the
debt register measures `epilogueMasked` at 7530 bytes for 16 calendar outputs and
8079 for 17, and 8000 is `HugeMethodLimit`, past which HotSpot compiles nothing
at all. Sixteen calendar outputs over one date column share one prefix under B1
instead of repeating fifteen, so the method that today falls off that cliff stops
doing so. That is a compilability win on a shape a user can actually write, it
needs no measurement to justify, and it is most of the mechanism tasks 43 and 44
would otherwise have to invent.

**B2 - the grouping change that buys the 1.5x. Gate it on the two-field case
first.** Extend the ceiling kernel to `year, month` and measure it. Two fields
share ~45 ops and pay ~5 each, so the op-count ratio is 1.9x - but the four-field
case delivered 1.5x against a 3.1x op-count ratio, i.e. about half, so two fields
should be expected around 1.25x-1.4x and could easily land lower. That
measurement costs an afternoon and decides whether `FUSED_CEILING` and the
budget-rule relaxation are worth their risk. If two fields clears ~1.3x at
AVX-512, build B2; if it lands near 1.1x, stop after B1 and record why.

Either way **the default cannot be flipped on the AVX-512 number alone**:
section 5.2's measurement must run at both widths on the emitted path, and
`shareChronoPrefix` has to be able to default differently from what AVX-512 alone
would choose. Whether the 128-bit bimodality follows the emitted body is the
first thing B2 should find out, since it has the same shape and the same register
demand - and per the section above, no JVM flag will make that question go away.

### 7.1 Step B1's outcome: the fragment is built, and the epilogue's cliff moves out

Built as planned, minus the grouping change, which stays with B2: `FragmentKind`,
`FragmentKey`, `chronoChild` and `fragmentKey` in `VarkaLoopEmitter`; `planSlots`
allocating the eight prefix locals once per fragment instead of once per node;
`emitChronoPrefixOnce` between `emitChrono`/`emitAddMonths` and the prefix; and
`VarkaEmitOptions.shareChronoPrefix`. `weightOf`/`addOps`/`groupOutputs` are untouched,
so every calendar output still gets its own loop method.

**The key is (kind, child, validity word), not (kind, child).** The plan wrote the key
as the kind and the child. That is not enough, because the prefix carries the range
guard and the guard is ANDed with the node's validity word: `planWordRef` aliases every
`Chrono` extraction's word to its child's, so `year(d)` and `month(d)` agree, but
`AddMonths`'s word is the AND of the date's and the month count's, so
`add_months(d, n)` over a nullable `n` must not inherit a guard computed under `year(d)`'s
mask. Adding the word to the key makes that structural rather than a rule someone has to
remember. In a dense body no word is planned at all and the child alone decides.

**One slot is written after the prefix, and it is deliberately outside the contract.**
`emitAddMonths` reuses `t[6]` - the prefix's carry mask - as scratch for its own compares.
That is sound because no field tail reads `t[6]`: the tails read only `t[1..5]`. The
contract is now stated in `emitChronoPrefix`'s javadoc rather than implied, and a test
orders `add_months` *before* the three extractions so a violation of it fails.

**The default was flipped in this step, not deferred to step 7.** Step 7 in section 9 is
about B2's throughput default, which needs both widths measured. B1's case is not a
throughput case at all - it is a compilability one, it needs no benchmark, and with the
option off it delivers nothing. Flipping it moved no pinned oracle: the line map, the
`everyNode` fixture and the shape hash are all unchanged, because `canonical()` renders
options only when they differ from `DEFAULTS` and `DEFAULTS` is what moved.

**No parity regeneration, and the reason is structural rather than a judgement.** Under
today's grouping no loop method holds two calendar nodes, so there is nothing in one for
the fragment to share and every `loopDense`/`loopMasked` method is byte for byte what it
was - asserted by a test, not by inspection. The parity benchmark drives 4096-row chunks,
which every lane count divides, so its epilogue returns at the length check and is never
timed. No committed figure can therefore have moved, and re-running the file would have
added a run's worth of noise to numbers this change cannot reach. The same test fails the
moment B2 relaxes the budget, which is the signal to regenerate.

#### The ladder

`epilogueMasked`'s bytecode size, four calendar fields per date over as many dates as the
width needs, measured through `VarkaEmitterTestSupport.codeSize` (the `Code` attribute's
length, which is what HotSpot measures against `HugeMethodLimit`):

| outputs | dates | unshared | shared |
|---|---|---|---|
| 1 | 1 | 662 | 662 |
| 2 | 1 | 1088 | 732 |
| 4 | 1 | 1964 | 896 |
| 8 | 2 | 3817 | 1681 |
| 16 | 4 | 7531 | 3259 |
| 17 | 5 | **8080** | 3808 |
| 20 | 5 | 9436 | 4048 |
| 24 | 6 | 12043 | 4837 |
| 32 | 8 | 17215 | 6421 |
| 40 | 10 | 22443 | **8025** |
| 48 | 12 | 27913 | 10405 |

The 8000-byte crossing moves from **17 outputs to 40** - from four date columns to ten.
Past it HotSpot compiles the method not at all, so the epilogue runs interpreted with
boxed vectors on every batch whose length is not a lane multiple. (The debt register
records 7530 and 8079 for the same two shapes; the numbers here are one byte higher
because they are the `Code` attribute's length. Same measurement, same conclusion.)

This does not close tasks 43 or 44. Task 43 is about a single output holding several wide
nodes, which the fragment does not touch, and a wide enough projection still crosses -
it now takes ten date columns instead of five. What it does is give task 44 a real
baseline instead of a cliff four columns away.

**Update: every number in this ladder moved again, and for an unrelated reason (task 51).**
Task 51 removed the per-extraction range guard `emitEra` carried since task 26 - two
compares, ANDed with validity and the epilogue mask, ORed into an accumulator, on *every*
calendar node's tail, shared or not. Deleting that bytecode shrinks every row above, not
just the shared column, since the guard was never something sharing touched one way or the
other - it lived in `emitEra`, which both the shared and unshared paths call. Re-measured
the same way, after task 51:

| outputs | dates | unshared | shared |
|---|---|---|---|
| 1 | 1 | 599 | 599 |
| 2 | 1 | 980 | 669 |
| 4 | 1 | 1766 | 833 |
| 8 | 2 | 3439 | 1573 |
| 16 | 4 | 6793 | 3061 |
| 17 | 5 | 7297 | 3565 |
| 18 | 5 | 7680 | 3637 |
| 19 | 5 | **8073** | 3719 |
| 20 | 5 | 8506 | 3805 |
| 24 | 6 | 10895 | 4549 |
| 32 | 8 | 15675 | 6043 |
| 40 | 10 | 20485 | 7537 |
| 44 | 11 | 22875 | **8630** |
| 48 | 12 | 25329 | 9765 |

The crossing moves again, from 17/40 to **19/44** - two more outputs fit unshared, four
more shared, before either crosses.

**Update: the unshared column moved a third time (task 48).** The year tail now reads its
January bit off the day of year rather than off the March-based month, so a prefix whose
only consumer is a `Year` skips the month step entirely - four lane ops and a store. Under
sharing that changes nothing in this table, because the epilogue holds every output and so
every fragment here has a `Month` consumer; unshared, every `Year` node has its own prefix
and every row loses one month step per date. Re-measured the same way again:

| outputs | dates | unshared | shared |
|---|---|---|---|
| 1 | 1 | 575 | 575 |
| 2 | 1 | 956 | 670 |
| 4 | 1 | 1742 | 834 |
| 8 | 2 | 3391 | 1575 |
| 16 | 4 | 6697 | 3065 |
| 17 | 5 | 7177 | 3545 |
| 18 | 5 | 7560 | 3642 |
| 19 | 5 | **7953** | 3724 |
| 20 | 5 | **8386** | 3810 |
| 24 | 6 | 10747 | 4555 |
| 32 | 8 | 15467 | 6051 |
| 40 | 10 | 20217 | 7547 |
| 44 | 11 | 22612 | **8639** |
| 48 | 12 | 24989 | 9777 |

The unshared crossing moves from 19 to **20**; the shared one stays at **44**, gaining only
the one byte a year tail's `sipush 306` costs over a `bipush 10`. That is three moves of the
same number for three unrelated reasons - sharing, the guard's removal, and now the month
elision - which is the strongest argument yet that task 44 should measure its own baseline
when it is picked up rather than inherit any ladder recorded here. `VarkaLoopEmitterSuite`'s
`"sharing the prefix moves the epilogue's HugeMethodLimit crossing from 21 outputs to 44"`
test carries the new numbers (its title has since moved twice more: task 48 to 20, task 54's
shorter prefix to 21, shared still 44 - `PLAN_TASK_54.md` section 9 has that ladder); this section keeps the original ladder above it rather than
overwriting it, since both were true measurements of the emitter at the time they were
taken, and a reader tracing why a number changed should be able to see both. The same
caveat task 32's own note above already raised - "this does not close tasks 43 or 44" -
applies again, with the new numbers.

#### Predictions, scored

6. **Nearly right, and the miss is worth recording.** Predicted `epilogueMasked` at 16
   calendar outputs over one column drops from 8079 bytes to under 3000. Two corrections.
   First the shape: 16 calendar outputs over *one* column do not exist - the IR's records
   compare by value, so four fields over one date are four nodes and repeating them is
   free. The debt register's 16 was four fields over four dates, and the prediction
   inherited the misreading. Second the number: 7531 to 3259, not under 3000. The
   direction and the mechanism were right and the magnitude was 9% optimistic, which is
   the better failure mode than step A's prediction 1.

#### What B2 still needs

Unchanged from the section above: the two-field measurement gates it. One thing B1 adds
is that the shape is already known to be *correct* - a test drives four calendar outputs
through one loop method under a widened `groupBudget`, and the exhaustive sweep over all
16,777,216 days of the covered range now runs under both settings - so B2 is a
measurement and a policy decision, with no correctness work left in front of it.

### 7.2 Step B2's gate: measured, and it clears - plus two findings the gate did not ask for

Section 7's gate: extend the ceiling to `year, month` and measure it; build B2 if it clears
~1.3x at AVX-512. With B1's fragment already built, the actual B2 shape - the emitted path
under a widened `groupBudget` - can be measured directly rather than approximated, which is
better evidence than the gate asked for. Six new cases in `VarkaEmitterParityBenchmark`'s
"year" section (`VarkaEmitOptions.DEFAULTS.withGroupBudget(200)`, comfortably past four
fields' 200 ops so `groupOutputs` fuses every calendar output into one method, where
`shareChronoPrefix` - on by default since B1 - then shares the prefix once): `year+month` and
`year+month+day` each measured separate and shared, `year+month+day+quarter` shared against
the existing four-node baseline and the hand-written ceiling, and `year(d1), year(d2)` as the
regression guard section 5.2 names.

Three runs, AVX-512, idle machine, five iterations per case per run; ratios compared by the
minimum best-time across the three runs where the ratio came in near the 1.3x re-check line,
per the project's standing rule:

| fields | separate (M rows/s) | shared (M rows/s) | ratio (min best-time) |
|---|---|---|---|
| 2 (`year, month`) | 902.2-914.6 | 1174.3-1244.2 | **1.29x** (22ms / 17ms, identical across all 3 runs) |
| 3 (`year, month, dayofmonth`) | 587.9-604.8 | 892.4-934.4 | **1.57x** (33ms / 21ms) |
| 4 (`year, month, dayofmonth, quarter`) | 437.6-444.6 | 761.7-799.8 | **1.80x** (45ms / 25ms) |

**The gate clears, and the win grows with field count rather than shrinking.** Prediction 3
(section 6) expected a *sublinear* gain - 1.6-1.9x at two fields, 2.2-2.7x at three - reasoning
from the four-field op-count ratio the way prediction 1 did, which section 7 already found
overstates a sharing win by about 2x. The corrected shape is the opposite of what was
predicted: two fields sit at the stated 1.3x bar almost exactly (1.29x, stable to the ms
across three separate JVM runs, not a fluke), and the ratio climbs through 1.57x to 1.80x as
more of the loop method's fixed per-lane-group cost (section 2.17) gets to amortize over more
outputs. **Recommendation: build B2.** The two-field number is not a comfortable clearance of
the stated bar, but it is a stable one, and it is the worst case in the table rather than the
typical one - every additional field makes the case stronger. The owner's call, per section
2's decision to have the owner pick after seeing the numbers - but there is no reading of this
table where declining B2 is the safer choice.

**Finding 1: the hand-written "ceiling" was measuring the masked body, not the dense one, and
the true dense-body number beats it.** `ChronoVectorOps.vectorFourFields` builds a
`VectorMask` and uses masked load/store overloads on every lane group regardless of
`hasNulls` - it has no separate unmasked path, because it predates task 10's dense/masked
split existing as a *variant* the way the emitter has it. The emitted kernel does have one,
and on null-free data (`srcNullCount == 0`) the driver dispatches to the true dense body -
unmasked loads and stores, no `VectorMask` machinery at all. Measured across the same three
runs: the emitted four-field **shared** kernel (25ms best time) is reliably **1.15-1.20x
faster** than the hand-written **ceiling** (30ms, identical across all three runs) on the
same null-free data the ceiling was measured on. A kernel named "ceiling" that a real,
already-built path exceeds is not a ceiling; it is a masked-body measurement that happened to
read as one because nothing dense existed to compare it with until B1/B2 made the emitted
path buildable. This does not retroactively wrong step A's own number (679.0 vs 445.7,
1.52x) - both sides of *that* comparison were dense-dispatched, since the four independently
emitted nodes get the same dispatch the shared kernel does - but it does mean `ChronoVectorOps`
should not be read as an upper bound on what a dense null-free shared kernel can reach. It is
a same-arithmetic reference kernel, and a masked one; the emitted path is the ceiling now.

**Finding 2: the validity write is just over half the ceiling kernel's time - not three
quarters, and not a fit.** Section 2.17 asked this question from a three-point fit across
different op counts and called it "a fit, not a measurement." `ChronoVectorOps.vectorFourFieldsNoValidity`
is the direct measurement: the exact same arithmetic, the exact same guard, with every
destination validity buffer and every `orValidityBitsAt`/`orPartialValidityBitsAt` call
removed (`ChronoVectorOpsTest.noValidityMatchesFourFieldsOnNullFreeData` pins that removing
them changes no value). Across the same three runs the ceiling (with validity) costs
1.50-1.52 ns/row and the no-validity variant costs 0.65-0.67 ns/row - a difference of
0.84-0.85 ns/row, which is **55.6% to 56.7% of the ceiling's total time**, spent on four
`zero()` calls and, per lane group, four `orValidityBitsAt` calls that write back a value
already implied by the guard mask. This is a different number from section 2.17's fitted
"three quarters is fixed per-lane-group cost" because that fit priced *everything* the
decomposition does not touch (the four stores, the chunk prologue, the loop control), not
validity alone; validity is the majority of that fixed cost but not all of it. Tasks 45-47
are confirmed worth doing on this number alone, with 45 (the null-free fast path, one fill
per batch instead of one OR per lane group per output) the most directly targeted at it.

### 7.3 Task 44's crossing, priced directly rather than inferred from bytecode size

Section 7.1 measured `epilogueMasked`'s *bytecode size* crossing `HugeMethodLimit` at 40
outputs; it did not measure what crossing it costs, because no committed harness runs a
non-aligned chunk on a wide enough calendar shape - the four-field ladder in section 7.1's own
table stays under the limit unshared (7531 bytes at four dates) and so has nothing to cross.
A new section, five date columns of four fields (20 calendar outputs: 9436 bytes unshared,
past the limit; 4048 bytes shared, comfortably under it), at the same four chunk sizes task
24's ladder uses:

| chunk | unshared (ns/row) | shared (ns/row) | ratio |
|---|---|---|---|
| 4096 (aligned) | 13.9 | 13.7 | 1.01x |
| 4095 (lanes-1 tail) | 18.7 | 13.7 | **1.36x** |
| 64 (aligned) | 64.0 | 46.9 | **1.36x** |
| 63 (lanes-1 tail) | 410.5 | 56.1 | **7.32x** |

Two things this shows that the size ladder alone could not:

**The crossing costs something even on an aligned batch.** `emitEpilogue`'s own generated
body returns immediately when `loopBound == length` (`Label remainder; ... ireturn`), so at
chunk 4096 and chunk 64 the epilogue never does real work under either setting - and yet chunk
64 shows a stable 1.36x cost anyway. The reason is that the epilogue method is *called*
unconditionally on every `run` invocation regardless of remainder, and an interpreted call
into a method HotSpot will never compile at any tier is measurably slower than a compiled
one's early return - invisible at chunk 4096, where 4880 calls per iteration are swamped by
real vector work, and visible at chunk 64, where 312500 calls per iteration make the per-call
difference the dominant cost. **This means the debt register's framing - "runs interpreted...
on every batch whose length is not a lane multiple" - understates the cost surface: the method
is invoked, and pays an interpreter-call tax, on every batch, aligned or not; it only does
interpreted *arithmetic* on the unaligned ones.** Section 2.9 and the debt register are
corrected to say so, since a reader relying on the old framing would conclude a production
workload of aligned 4096-row batches pays nothing for a method past the limit, which chunk
64's number says is not quite true even if the effect is small at that width.

**Where the effect is real work, it is not small.** At chunk 63 - the closest committed shape
to "most of a batch is remainder," which no production query produces but which isolates the
mechanism - the unshared kernel is **7.3x slower**: fifteen rows of civil-from-days arithmetic
over twenty outputs, run to completion by the interpreter with boxed vectors, dominates the
sixty-three-row call. At the shape closer to production, chunk 4095 (one lane group's worth of
remainder out of 4096, i.e. a batch shy by one row of the shipped `COLUMN_BATCH_SIZE`), the
cost is a measured 1.36x - the honest number for "what does a fifteen-column-of-dates,
sixteen-plus-field-wide query pay for landing one row short of an aligned batch," which is a
question worth having a number for even though such a projection does not exist in the
milestone's corpus today.

### 7.4 B2 at 128-bit: the gate clears there too, and the bimodality does not travel

Section 7.2 measured the B2 gate at AVX-512 only; the plan is explicit that the default
cannot be chosen on that number alone, because the hand-written ceiling kernel showed a
128-bit bimodality that six hypotheses failed to explain (section 7's own record). The open
question was whether that bimodality belongs to the *mechanism* (a wide shared calendar body
at a narrow vector width) or to the *specific kernel* it was found on. Three runs of the same
B2-gate cases under `-XX:MaxVectorSize=16`, one of them run after an unplanned reboot of the
machine confirmed the environment was otherwise unaffected:

| fields | separate (min best-time, ms) | shared (min best-time, ms) | ratio |
|---|---|---|---|
| 2 (`year, month`) | 63 | 48 | **1.31x** |
| 3 (`year, month, dayofmonth`) | 94 | 64 | **1.47x** |
| 4 (`year, month, dayofmonth, quarter`) | 127 | 76 | **1.67x** |

Against the AVX-512 numbers (1.29x / 1.57x / 1.80x), these are close - the emitted B2 kernel
wins by nearly the same factor at both widths, growing with field count at both. Every one
of these six cases (separate and shared, all three field counts) was stable to a few
milliseconds across all three runs, with no run showing the outlier pattern the two kernels
below show. **The gate clears at 128-bit too, by nearly the same margin as at AVX-512.**

**The bimodality belongs to `ChronoVectorOps`, not to "a shared calendar body at 128-bit."**
Across the same three runs, the *other* two kernels this task built - both hand-written, both
predating the emitted fragment - reproduce exactly the instability section 7 already
documented and failed six times to explain:

* **The hand-written ceiling** (`vectorFourFields`): 121ms, 84ms, 121ms - the same roughly
  2-of-3-slow, 1-of-3-fast split the earlier 21-run investigation found (4 fast out of 21).
  Even at its fastest observed run the emitted shared kernel (76ms) still beats it (84ms,
  1.11x); at its ordinary, more frequent speed the margin is 1.59x.
* **The no-validity variant** (`vectorFourFieldsNoValidity`): a *different* flavor of the same
  instability, and a new data point for the open investigation. Two of three runs show a
  "Best Time" of 32ms sitting beside an *average* of 1261-1291ms and a stdev over 2000ms -
  meaning some individual timed iterations inside one JVM run land fast and others land
  catastrophically slow, flipping mid-run rather than settling into one mode for the run's
  duration the way the ceiling kernel does. The third run is stable at 32ms throughout. This
  is worth recording as a seventh data point for a bimodality that, per section 7's own
  conclusion, no JVM flag reaches: it is a property of C2's code generation for these two
  specific 128-bit method bodies, not of the sharing technique, and not, per this section, of
  the emitted path that implements the same technique in different bytecode.

The practical consequence: **do not quote a 128-bit `ChronoVectorOps` number from a single
run.** Every number from `vectorFourFields` or `vectorFourFieldsNoValidity` at 128-bit in this
document or elsewhere is either an explicit multi-run range or should be read as one mode of
an unresolved bimodal pair - the emitted kernel carries no such caveat, at either width.

### 7.5 The compile cliff (risk 1 / prediction 4): measured, and it did not happen

`GROUP_BUDGET` exists because a 64-op loop method took a ~10-second tier-4 compile in this
project's own earlier findings (`SKILLS.md`, "C2 Compile Latency Is the Wide-Vector-Loop
Cliff"), and the plan named this the risk most likely to cap B2 - "the one thing that could
cap this," measured before `FUSED_CEILING` is chosen rather than discovered after. Every
number reported in 7.2 was steady-state throughput; none of it says how long the widened
kernels took to reach that steady state; that is what this section measures.

`-XX:+PrintCompilation` across the whole parity benchmark run, filtered to each generated
class's own name (every kernel in this file gets a distinct numbered class, so this is exact,
not a guess about which lines belong to which kernel):

| kernel | shape | wall time, first tier-3 compile to every method's tier-4 landing |
|---|---|---|
| `VarkaFusedBench806` | 2 fields, 1 method, 100 ops | 142 ms |
| `VarkaFusedBench808` | 3 fields, 1 method, 150 ops | 207 ms |
| `VarkaFusedBench809` | 4 fields, 1 method, 200 ops (the B2 case measured in 7.2) | 272 ms |
| `VarkaFusedBench803` | 4 fields, 4 separate methods (today's shape) | 624 ms |
| `VarkaFusedBench900` | 20 outputs, 20 separate methods, unshared (task 44's crossing) | 2434 ms |
| `VarkaFusedBench901` | 20 outputs, 1 method, 200 ops, shared | 1971 ms |

None of these approach the historic 10-second cliff, and none show the cliff's own tell: a
repeated tier-4 task line marked `blocked` (zero occurrences anywhere in the log, for any
Varka-generated class) or a compile that never lands. Every method compiles exactly once past
tier 3 (a normal, single "made not entrant: not used" per method as tier 4 supersedes it), with
one expected exception: `run`/`epilogueDense` on the kernels that later see a masked or
larger-remainder batch for the first time take a second, equally fast recompile via an
`uncommon trap` deopt when that shape is first exercised - not thrashing, just C2 learning a
branch it had not yet profiled.

**809's own timeline is the direct answer to the risk.** The case "year+month+day+quarter,
shared (1 loop method), null-free" begins, `loopDense0` (776 bytes, the 200-op body) reaches
an OSR tier-4 compile 58ms after its first tier-3 compile and a standard tier-4 compile 171ms
after that; every dependent method (`run`, `runDense`, `epilogueDense`) is fully tier-4 within
272ms of the case starting. Against a 2-second warmup window, that leaves roughly 1.7 seconds
of genuinely steady-state execution before the measured window even opens - the throughput
numbers in 7.2 are not measuring a partially-compiled kernel.

**900 is the outlier worth naming, and it still is not a risk.** Twenty separate 1000-byte-ish
loop methods take a combined 2.4 seconds to all reach tier 4 - the longest compile window in
the whole suite, because there are twenty independent compile tasks rather than one or four.
That is close enough to the 2-second warmup window that it is worth flagging rather than
waving past: the case's own committed stdev (3ms on a 278ms best time, in the run this
section's numbers are quoted from) shows no sign of contamination, and `Benchmark`'s
best-time statistic is by construction robust to one slow early iteration even when a stdev is
not - but this is the one case in the suite where the compile-cliff risk was closest to
mattering, and it mattered at 20 outputs, not at four.

**Prediction 4, scored:** wrong in the same direction as prediction 1 was wrong in step A, and
for a related reason - both predictions reasoned from the *historic* 64-op finding on a
different kernel (a hand-written body compiled by `javac`) rather than measuring the actual
emitted bytecode this task produces. The emitted path's own methods compile in low hundreds of
milliseconds even at 200 ops in one method, and the historic cliff does not reproduce anywhere
in this file's kernels. Confidence was stated as low ("the one most likely to be wrong, and
the one with a real chance of capping the mechanism"); it was wrong, in the favorable
direction.

### 7.6 Step B2's outcome: built, the default, and the ceiling set by the ladder

Section 10's plan, executed on master at aef0b82260e (task 46 merged) in four
commits: the weights recounted and `fusedCeiling` added as an option with the
rule not yet wired (no emitted byte moved, asserted by the pre-B2 byte-identity
test); the rule; the ladder and one regeneration of the parity file at both
widths; the record swept. Everything below is from that regeneration
(`VarkaEmitterParityBenchmark-jdk25-results.txt`, `-128bit-results.txt`,
provenance beside them) unless it says otherwise.

**The weights, recounted first** (10.3), because clause 2 sums the tails against
the ceiling and a weight that only had to exceed `GROUP_BUDGET` is wrong the day
it bounds a method. Read off `loopDense0`'s `IntVector` calls with
`dev/varka_emit.sh`, each node alone and beside `month(d)` in one method; the
pair minus `month(d)`'s own 35 is the tail, and `VarkaLoopEmitterSuite` pins
every line against the emitted bytes:

| node | prefix | tail | weight now | weight before |
|---|---|---|---|---|
| `year`, `month`, `dayofmonth`, `quarter` | 31 (29 where task 48 elides the month step) | 5, 4, 5, 7 (one constant at 7) | 38 | 40 |
| `dayofyear` | 31 | 14 | 45 | 51 |
| `last_day` | 31 | 32 | 63 | 40 (borrowed `CHRONO_WEIGHT`) |
| `add_months` | 31 | **81** | 112 | 40 (borrowed) |
| `trunc` `YEAR` / `MONTH` / `QUARTER` | 31 | 16 / 5 / 31 | 47 / 36 / 62 | 53 / 40 / 70 |
| `trunc` with a format column | 31 | 60 | 91 | 99 |
| `weekofyear` (prefix over the shift) | 31 | 16 | 47 | 55 |

10.2's illustration assumed a 44-op prefix and 6-op tails. The prefix is 31 and
the field tails are 4 to 7, so four fields are 52 ops in one method rather than
62 - but `add_months`'s tail is 81, thirteen times a field's, and that is the
number that decides how many outputs a ceiling admits: the four fields together
weigh less than one `add_months`. 10.4's ladder rows therefore weigh 214, 376
and 700 ops rather than the ~120 to ~200 the plan expected, and the two
candidate ceilings split them where the plan expected only the widest to split.

**The rule, and what it groups.** Clause 2 as 10.2 wrote it - `saved > 0`, the
group within `fusedCeiling` - with `saved` counting prefix reuse only, keyed on
the date the node decomposes (the dense body's fragment key; the masked body's
key also carries the validity word, so a group may hold two outputs whose
masked bodies do not share while the dense body and the epilogue do - the
conservative side, never a wrong grouping). Pinned by loop-method count in the
suite: four fields over one date, one method; the same with sharing off, four;
`year(d1), year(d2)`, two; `add_days, sub_days`, one; `x + 1, year(d), month(d)`,
two; the four fields under a ceiling of prefix plus two tails, two methods of
two; `year(d), year(d2), month(d)`, three (10.2's limitation, pinned as one)
against two when adjacent; and `weekofyear(d), yearofweek(d)`, one - task 58's
debt, closed by the rule rather than by task 44's decision. Non-calendar shapes
- task 17's pair, a depth-8 chain, `CASE WHEN`, `greatest`/`least`, the mod-7
family, `datediff` - emit byte-identical loop methods with sharing on and off,
which is the guard that clause 2 reaches nothing but fragment reuse.

**The numbers, the "year" section** (2, 3 and 4 fields; "separate" is now
`withShareChronoPrefix(false)`, "shared" the defaults; the forced
`withGroupBudget(200)` rig is gone):

| fields | AVX-512 separate | shared | ratio | 128-bit separate | shared | ratio |
|---|---|---|---|---|---|---|
| 2 | 1713.7 | 2737.9 | 1.60x | 664.0 | 1027.9 | 1.55x |
| 3 | 1125.4 | 2154.8 | 1.91x | 429.7 | 871.6 | 2.03x |
| 4 | 818.0 | 1762.6 | 2.15x | 315.2 | 792.5 | 2.51x |

The four-field mixed-null row reads 1124.0 at AVX-512 and 417.7 at 128-bit
(1050.7 and 408.7 in the file this branch started from, where it was already
the shared shape under the rig). The `year(d1), year(d2)` guard row is 1663.5
and 662.4, and is not comparable to the 1606.6 / 658.3 before it: under the rig
that kernel was one method holding two prefixes (clause 1 at a budget of 200),
under the defaults it is the two methods it should be.

**The ladder** (10.4; `add_months(d, k)` over the same date, distinct literals,
null-free, 4096-row chunks; "one method" is a ceiling of 1000, which nothing
here reaches; the two candidates split the row wherever it exceeds them, and
the case names in the file carry each arm's loop-method count):

| outputs | ops | AVX-512 unshared | one method | ratio | at 200 | at 400 | 128-bit unshared | one method | ratio | at 200 | at 400 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 6 | 214 | 249.5 | 454.4 | 1.82x | 344.3 (2 methods) | 453.0 (1) | 89.9 | 168.3 | 1.87x | 122.9 (2) | 168.9 (1) |
| 8 | 376 | 144.9 | 240.1 | 1.66x | 188.3 (3) | 240.8 (1) | 51.6 | 89.0 | 1.72x | 67.8 (3) | 89.1 (1) |
| 12 | 700 | 76.0 | 136.5 | 1.80x | 94.8 (5) | 124.7 (2) | 27.3 | 53.3 | 1.95x | 33.9 (5) | 44.1 (2) |

One method wins at every row and both widths, and every split costs: at twelve
outputs the ceiling of 400 (two methods) gives up 9% at AVX-512 and 17% at
128-bit against one method of 700 ops, and the ceiling of 200 (five methods)
gives up 31% and 36%. Rule (i) of 10.4 - the one-method arm beats the unshared
arm at both widths - holds through the widest row measured. Risk 4 (a 128-bit
register-pressure cliff past four outputs) did not appear: the 128-bit ratios
are the larger ones throughout.

**Compile time** (rule (ii)), each kernel run hot on its own under
`-XX:+PrintCompilation` through `VarkaEmitDump --rounds 300000`, wall time from
the class's first tier-3 compile to its last method's tier-4 landing:

| kernel | loop methods | loop method(s) at tier 4 after | every method at tier 4 after | C1 refused (`out of virtual registers in LIR`) |
|---|---|---|---|---|
| 4 fields, 52 ops | 1 (626 bytes) | 93 ms | 147 ms | nothing |
| 6 outputs, 214 ops | 1 (1898 bytes) | 337 ms | 547 ms | the epilogue (1925 bytes) |
| 8 outputs, 376 ops | 1 (3174 bytes) | 340 ms | 894 ms | the loop and the epilogue |
| 12 outputs, 700 ops | 1 (5728 bytes) | 556 ms | 1877 ms | the loop and the epilogue |
| 12 outputs at a ceiling of 400 | 2 (3296, 3136 bytes) | 623 and 1136 ms | 1713 ms | both loops and the epilogue |
| 12 outputs at a ceiling of 200 | 5 (1312 to 1920 bytes) | 808 to 1306 ms | 1429 ms | the epilogue |

Every method reached tier 4 once and stayed there - no `blocked` task, no
`COMPILE SKIPPED` from C2. What did appear is C1's refusal: past about 1900
bytes the tier-3 compile of a loop method is skipped for want of virtual
registers (task 43 saw the same refusal land on the epilogue; at eight shared
outputs it reaches the loop), so until C2 lands the method runs interpreted
rather than at C1 speed - a slow start of a third of a second at eight outputs,
paid once per shape per JVM behind the task-18 class cache. And the epilogue is
what makes the twelve-output kernel slow to settle, at any ceiling: it holds
every output whatever the loop grouping, its 5767 bytes are the last tier-4
landing in all three twelve-output runs, and splitting the loops does not
bring the kernel under a second (1713 ms at two methods, 1429 ms at five
against 1877 ms at one) - that is task 44's method, not this task's.

**The ceiling: 400.** Rule (i) holds through the widest row measured, so rule
(ii) decides: eight outputs, 376 ops, is the widest row whose kernel is fully
at tier 4 inside a second (894 ms); twelve, 700 ops, is not (1877 ms), and its
loop alone, at 556 ms, would pass a rule about the loop - but the rule was
written about the kernel, and it is kept as written because the interpreted
start grows with the method (340 ms at 376 ops, 556 ms at 700). So
`FUSED_CEILING` ships at 400, the plan's upper candidate, which admits the four
fields plus four `add_months` over one date, or - by the register - about
twenty field-sized outputs. What it costs at the one row past it: twelve such
outputs run as two methods at 124.7 against one method's 136.5 (AVX-512) and
44.1 against 53.3 (128-bit), 9% and 17% below the unbounded shape and still
1.6x over the unshared one. A projection that wide over one date has not been
seen in the corpus; if one turns up, the option is there to price a wider
ceiling on it.

**Predictions of 10.6, scored.**

1. *The 2/3/4-field ratios reproduce 7.2 and 7.4 within run noise.* **Held,
   against the file's own post-task-45 values rather than 7.2's**: the file this
   branch started from already read 1.59x / 1.84x / 2.07x at AVX-512 and 1.56x /
   2.00x / 2.52x at 128-bit under the rig, and B2's rule gives 1.60x / 1.91x /
   2.15x and 1.55x / 2.03x / 2.51x - the same bytes, as predicted, and the same
   numbers to within a few percent. 7.2's 1.29x / 1.57x / 1.80x were measured
   before task 45, and prediction 6 said why they would rise: the validity write
   was the same absolute cost on both sides of the ratio, and 45 took it off
   the dense path.
2. *Eight outputs beat their unshared shape by at least 2.0x at AVX-512 and 1.5x
   at 128-bit.* **Missed at AVX-512 (1.66x), held at 128-bit (1.72x)**, and the
   miss is the weight finding above: the prediction reasoned from "more outputs
   amortise the same fixed cost" over field-sized tails, and an `add_months`
   tail is 81 ops of real work per output that sharing cannot touch. By op count
   alone the rows go 376 to 214, 600 to 376 and 1048 to 700 ops (1.76x, 1.60x,
   1.50x); the measured 1.66x to 1.95x is that plus the per-method fixed cost
   7.2 identified, and the gain does not shrink past four - twelve outputs at
   1.80x / 1.95x sit above eight at both widths.
3. *Compile time stays under one second through the 12-output row at AVX-512;
   the ceiling ships at 400.* **Half held.** Under one second through eight outputs (376
   ops, 894 ms), not through twelve (700 ops, 1877 ms) - and the miss is the
   epilogue's tier-4 landing, which no ceiling controls, more than the loop's
   (556 ms). The ceiling ships at 400 as the prediction said, by the rule it
   named, for the row the prediction did not expect to be the last one: the
   plan's arithmetic had twelve outputs at ~400 ops, and the recounted
   `add_months` tail puts them at 700. Confidence was medium-low, and the
   direction of the miss was the one the plan named ("most likely to set the
   ceiling lower").
4. *No non-calendar committed number moves, asserted by construction.* **Held**
   by the byte-identity test over the non-calendar corpus, which is the claim
   the prediction made; the regenerated file's non-calendar rows differ from the
   previous file by run noise, and the one non-calendar pair whose reading
   changed sign did so one regeneration before this branch (task 17's; the
   finding below).
5. *No pinned oracle moves.* **Held**: the line map and the shape hash are
   unchanged, `DEFAULTS` still renders empty, and the suite's pinned tests pass
   as written.

**Three findings the ladder did not ask for.**

* **Task 17's rows reversed one regeneration before this branch, and nobody
  noticed.** The committed file's "budget 16 (shipped): two loop methods" and
  "budget 24: one loop method, cross-output CSE kept" rows read 16 ahead by
  ~1.4x in every regeneration from task 48 (1852ba33ec4) through task 61
  (00eeed82279: 4487.4 against 3253.2), and 24 ahead by 1.3x in the next one,
  aef0b82260e (4237.4 against 5492.1) - task 46's second half, which moved the
  validity OR ahead of the vector work so that C2 inlines it. This regeneration
  reads the same way at both widths: 4511.7 against 5799.7 at AVX-512, 1700.1
  against 2754.8 at 128-bit. So the loss task 17 measured, and this file, the
  `GROUP_BUDGET` javadoc and `SKILLS.md` all cited as register pressure, was
  most plausibly the refused `orValidityBitsAt` call in the wider method (task
  46's own finding, applied to a shape task 46 did not look at). B2's rule does
  not depend on which way those rows read - `saved > 0` is a property of the
  shape - and this task deliberately does not retune `GROUP_BUDGET` on the
  evidence (10.9): that is task 43's question, now with a live data point. The
  javadoc, `SKILLS.md` and this file's earlier sections say so where they quoted
  the old numbers as current; the earlier sections' text is otherwise left as
  written.
* **The task-44 section's shared rows moved 87% to 119%** ("shared (4048B
  epilogue, under HugeMethodLimit)", twenty outputs over five dates, 120.2 to
  224.8 at chunk 4096): five dates are five prefixes, so clause 2 groups the
  twenty outputs into five methods of four fields instead of twenty methods of
  one. The unshared arm is unmoved. This is the end-to-end shape 7.3 priced the
  epilogue crossing on, and B2 moved its loop side rather than its epilogue.
* **Task 58's row closed by a factor the debt entry did not predict.**
  "weekofyear + yearofweek, one shift" reads 1401.8 against 813.8 before, at
  AVX-512, and 509.2 against 298.0 at 128-bit - within 1% of `weekofyear` alone
  at both widths (1412.6, 510.8). The entry predicted 0.83x of `weekofyear` for
  the pair; the pair costs essentially nothing more than one field, because the
  `Year` tail over an already-decomposed day is five ops.

## 8. Risks

1. **Prediction 4.** ~~The compile cliff is the one thing that could cap this, and
   it is measured in 5.3 before `FUSED_CEILING` is chosen rather than discovered
   after the mechanism ships.~~ **Closed, section 7.5.** Measured directly via
   `-XX:+PrintCompilation` on every kernel in the parity suite: the widest single
   loop method (200 ops, four fields) reaches tier 4 in 272 ms, the widest kernel
   overall (twenty separate methods) in 2.4 s - neither the historic 10-second
   cliff nor its `blocked`-task tell appears anywhere. Did not cap the mechanism.
2. **A shared prefix under a changed guard.** Sharing the guard is correct only
   because the guard reads nothing but the child and the masks. Any future chrono
   node whose guard depends on something else (an ANSI throw path, task 30) must
   either join the fragment key or opt out of it; the differential in 5.1 is what
   catches a violation, and `FragmentKind` is where a second guard shape would
   live.
3. **Pinned oracles move.** The line map, the `everyNode` fixture and the shape
   hash all change under the new default. Expected; re-pinned in the same commit
   with a note, the way task 26 did.
4. **Merge order.** Five calendar PRs (#61, #62, #63, #64, #67) are open against
   `emitChrono`. Step B rebases on all of them and must be sequenced last;
   step A touches none of the emitter and can land immediately.
5. **The ceiling kernel remains unshippable.** It has no `VarkaFusedKernel`
   wiring - the engine module cannot depend on catalyst - so it stays a
   measurement artifact even with the guard added. That is fine for its purpose
   and is stated in its class doc; it must not drift into looking like a
   production path.

## 9. Sequencing

**Step A**, in the existing `varka-task-32` branch, rewriting PR #66 from
"declined" to "measured honestly":

1. Repair the kernel (hand-inline, guard, four validity buffers) and its test
   oracle; re-run the differential against `java.time` at both widths.
2. Generalize the benchmark helper, re-run, regenerate the committed results
   file in the same commit.
3. Rewrite section 2.9's outcome, the debt entry, `SKILLS.md`'s numbers, and the
   stale contingency in `PLAN_TASK_34.md`/`PLAN_TASK_35.md`.

**Step B**, new branch off master once PR #67 has landed, gated on step A's
number clearing the four-node baseline:

4. The emit option and the fragment plumbing, `shareChronoPrefix = false` still
   the default: bytes unchanged for every existing shape, and the differential
   from 5.1 green. Nothing pinned moves in this commit.
5. The grouping change and `FUSED_CEILING`, chosen from 5.3's compile-time and
   bytecode-size numbers.
6. The parity benchmark cases and one regeneration run.
7. The default flipped, pinned oracles re-pinned, `docs/sql-varka.md` and
   `README.md` requoted from that run, section 2.9 and the debt register swept in
   the past tense.

**What step B1 actually did**, recorded here because it departs from the list
above in two places (section 7.1 has the reasons): it branched off
`varka-task-40` rather than master, because the factoring it keys on lives only
there and re-doing it would have collided with PR #67 line for line; and it
carried out step 4 and the *default half* of step 7 together, because with the
option off B1 delivers nothing at all and the epilogue argument that justifies it
needs no measurement. Steps 5 and 6, and the parity regeneration, stay with B2
where the numbers they rest on are. Nothing pinned moved.

## 10. Step B2: the plan

Written after 7.2 and 7.4 cleared the gate at both widths and 7.5 measured the
compile cliff away. Everything a plan normally has to establish first is already
on the record above; what this section adds is the *mechanism* B2 ships, chosen
against the one the gate measured with, and the sequence that turns the cleared
gate into the default. It is written to be executable by someone who has read
sections 3.2, 7.2, 7.4 and 7.5 and nothing else about task 32.

### 10.1 What is already true, and what is not

* The fragment mechanism exists and is the default (B1, 7.1): two calendar nodes
  over one date in the *same method* run the prefix once. Nothing further is
  needed inside a method.
* No loop method ever holds two calendar nodes today. `weightOf` gives every
  calendar node `CHRONO_WEIGHT` (50) against `GROUP_BUDGET` (16), so
  `groupOutputs` splits before any second one, and the fragment only ever fires
  in the epilogue. That is the whole gap B2 closes: the hot loop, where the batch's
  rows actually are, still pays the prefix once per field.
* The gate (7.2, 7.4) measured B2's *shape* by forcing it -
  `VarkaEmitOptions.DEFAULTS.withGroupBudget(200)` - not by a grouping rule. A
  budget of 200 as the shipped default is not B2; it is the measurement rig, and
  it is the wrong mechanism for the reason task 17 already measured: a wide budget
  also merges *plain* chains, and on task 17's shape that loses (the committed
  file's "budget 16: 4436.3" against "budget 24: 3149.6" M rows/s). B2 must widen
  grouping for exactly the outputs that reuse a fragment and for nothing else.
* Section 3.2's fourth edit already sketched the rule and named the constant
  (`FUSED_CEILING`, candidate 96); 7.5 has since measured 200 ops in one method
  compiling in 272 ms. The rule below is 3.2's, tightened in one place where the
  sketch would have re-merged task 17's case.

### 10.2 The rule

Two changes to `groupOutputs`/`addOps` in `VarkaLoopEmitter`, both inert when
`shareChronoPrefix` is off.

**(a) `addOps` counts a fragment once per group.** Today a calendar node weighs
`CHRONO_WEIGHT` whole. Under B2 its weight splits into the prefix
(`CHRONO_PREFIX_WEIGHT`, counted the first time the node's `fragmentKey` is new to
the group) and its tail (`weightOf(node) - CHRONO_PREFIX_WEIGHT`, counted always).
The `seen` set `addOps` already threads gains fragment keys as synthetic members,
so `groupOutputs`' existing "count only what is new to the group" does the rest.
`addOps` also returns, beside the marginal op count, **how many ops the output
saved by reusing fragments the group already had** (`saved`); zero for any output
that reuses none.

**(b) A second join clause, admitting only fragment reuse.** Today:

    join when  ops + marginal <= budget                          (clause 1)

B2:

    join when  clause 1
          or   saved > 0 && ops + marginal <= fusedCeiling         (clause 2)

Clause 2 is what 3.2 wrote, with `marginal <= budget` replaced by `saved > 0`.
The difference matters on exactly one committed shape: task 17's two outputs over
a shared eight-op chain have `marginal = 6 <= 16` and would have joined under
3.2's wording, reversing a measured 1.4x loss; they have `saved = 0` and stay
split under this one. The clause admits an output only when joining lets it
*skip emitting a prefix the method already computed* - which is the one situation
where a wider method is strictly less work, not a trade.

Worked through the shapes this file has measured, with `CHRONO_PREFIX_WEIGHT`
= 44, tails 6, `fusedCeiling` = 200 for the illustration:

| outputs | grouping under B2 | why |
|---|---|---|
| `year(d), month(d), dayofmonth(d), quarter(d)` | one method, 62 ops | 50, then 6/6/6 each with `saved = 44` |
| `year(d1), year(d2)` | two methods | second has `saved = 0` and `50 + 50 > 16` |
| `add_days(d, 1), sub_days(d, 1)` (task 17's family) | as today | no fragment anywhere, clause 2 never fires |
| `x + 1, year(d), month(d)` | `[x+1]`, `[year, month]` | `year` has `saved = 0` against `[x+1]`; `month` joins `year` |
| `year(d), year(d2), month(d)` | `[year(d)]`, `[year(d2)]`, `[month(d)]` | greedy in output order; `month(d)`'s fragment is not in the group it is offered |

The last row is a limitation, not a bug: correct, and no worse than today, but
`month(d)` recomputes a prefix it could have shared had it been adjacent to
`year(d)`. **Reordering outputs to bring fragment siblings together is out of
B2** - the driver's output order is the projection's order and other things
(the evaluator's per-output vectors, `VarkaDebugInfo`'s line map) key on it. It
goes to the debt register with this row as the shape that would justify it.

**`fusedCeiling` is an emit option, like `groupBudget`**, so the ladder in 10.4
can vary it and a future retune is priced rather than argued: a new
`VarkaEmitOptions` field with `withFusedCeiling`, rendered in `canonical()`, with
the constant `FUSED_CEILING` in the emitter as its default. Its value is chosen in
10.4, not here.

### 10.3 Weights become honest, because they now bound a method

Today `CHRONO_WEIGHT = 50` for every calendar node "only has to exceed
`GROUP_BUDGET`", and `AddMonths` reuses it on that argument (its own javadoc says
so). Under clause 2 the tail weights are summed against `fusedCeiling`, so they
have to be what the node emits. B2 re-counts, from emitted instructions the way
`DAY_OF_YEAR_WEIGHT` was counted (PR #64), not from memory:

* `CHRONO_PREFIX_WEIGHT`: `emitChronoPrefix` after task 51 (PR #73) has removed
  the guard's compares and mask ops from `emitEra` - land B2 after #73, or count
  twice. Expected ~40.
* the four task-26 tails, `DayOfYear`'s (PR #64), `LastDay`'s (task 36, unmerged),
  and `AddMonths`'s - the last is the one most likely to be far from 6, since
  `emitAddMonths` recomposes with `emitDaysFromCivil` and a leap flag after the
  prefix.

Each becomes a named constant beside `CHRONO_WEIGHT`, and `weightOf` is
restated as prefix plus tail so it cannot drift from the split `addOps` uses.
`CHRONO_WEIGHT` itself stays as the sum for the unshared path and for the
`weightOf` doc's existing argument.

### 10.4 The ladder that sets `fusedCeiling`

7.2 and 7.4 cover one, two, three and four fields over one date. B2 needs to
know what happens past four, because clause 2 will merge every calendar output
that reuses the fragment, and a projection can carry more of them than the
task-26 quartet: `dayofyear`, `last_day`, and any number of `add_months(d, k)`
with distinct literals, each a distinct node over the same prefix.

A new section in `VarkaEmitterParityBenchmark`, same 4096-row chunks and
`eachChunk` walk as the "year" section, null-free data, five iterations over
two-second windows, an idle machine:

| outputs over one date | how they are made |
|---|---|
| 2, 3, 4 | the existing 7.2 cases, re-pointed at `DEFAULTS` vs `withShareChronoPrefix(false)` (see 10.5) |
| 6 | the four fields plus `add_months(d, 1)`, `add_months(d, 2)` |
| 8 | plus `add_months(d, 3)`, `add_months(d, 4)` |
| 12 | plus four more literals |

Each at two settings, `withFusedCeiling(200)` (7.5's measured-safe point) and
`withFusedCeiling(400)`, so the 12-output row - roughly 40 plus eleven
`add_months` tails - is only ever fused under the wider one, and the two rows
that straddle whatever `fusedCeiling` ships show the cost of the split it
imposes. At both vector widths, per the standing rule.

Two readings per row, per the standing rule that JIT facts come from the JVM:

* throughput, against the same outputs at `withShareChronoPrefix(false)`;
* `-XX:+PrintCompilation` wall time from first tier-3 compile to the last
  method's tier-4 landing, the 7.5 table extended down - the axis that decides
  the ceiling if throughput does not.

`fusedCeiling` is set at the widest row that (i) still beats its unshared
counterpart at both widths and (ii) compiles fully inside one second at AVX-512.
If every row through 12 clears both, it ships at 400 and the ladder is the
record; if the six- or eight-output row is where it stops paying, it ships
there, and that is still most of the win (7.2: the four-field case is 1.80x on
its own).

### 10.5 Files, and what moves

**`VarkaLoopEmitter.java`:** `addOps`/`groupOutputs` per 10.2; the constants per
10.3; `FUSED_CEILING`; the `GROUP_BUDGET` javadoc gains a paragraph saying what
clause 2 admits and that task 17's case is deliberately not it; `weightOf`'s
doc's last paragraph ("they simply do not share a method") rewritten.

**`VarkaEmitOptions.java`:** `fusedCeiling`, `withFusedCeiling`, `canonical()`.
`DEFAULTS` renders empty as before, so no production hash moves.

**`VarkaLoopEmitterSuite.scala`**, four tests:

* `"each calendar output gets its own loop method, whatever GROUP_BUDGET would
  say"` becomes its opposite for siblings over one date and keeps its plain-chain
  half: four fields over `col0` -> one `loopDense`; `year(d1), year(d2)` -> two;
  `add_days, sub_days` -> one, as today.
* `"sharing the prefix leaves every loop method byte for byte as it was"`: its
  own comment says B2 makes it fail and that this is the parity-regeneration
  signal. It is rewritten to assert what B2 promises instead - that for a corpus
  of **non-calendar** shapes (the task-17 pair, the depth-8 chain, the `CASE WHEN`
  and `greatest` cases the parity file already names) every loop method is byte
  for byte identical under `DEFAULTS` and `withShareChronoPrefix(false)`. That is
  the regression guard for clause 2 leaking past fragments, asserted by
  construction rather than by re-measuring task 17.
* the two-dates guard and the `year(d), year(d2), month(d)` ordering row from
  10.2, asserting method counts, so the limitation is pinned as a limitation.
* the existing `"the shared prefix survives two calendar outputs in one loop
  method"` drops its `withGroupBudget(200)` and runs under `DEFAULTS`, which is
  now the shape it was written to anticipate.

**`VarkaEmitterParityBenchmark.scala`:** the 7.2 cases lose `wideBudget` - under
B2 `DEFAULTS` *is* the shared shape, and "separate" is
`withShareChronoPrefix(false)`, which turns clause 2 off along with the fragment;
the 10.4 ladder section added. One regeneration run, and only one: the
"year" section's four-field row moves (that is the deliverable), the task-17
budget rows must not (that is the guard), and everything else in the file is
unreachable by this change.

**Docs:** `docs/sql-varka.md`'s fusion paragraph ("Today's grouping puts each of
those outputs in its own loop method, so this bites in the epilogue") is
rewritten to say what B2 does and to quote the regenerated four-field number;
`README.md`'s calendar line likewise if it carries a number.

**Plans:** this file's 7.6 (outcome, predictions scored), `PLAN_MILESTONE_4.md`
row 32 to **DONE**, section 2.9's closing paragraph, and the debt register's
"computed once per output" entry swept in the past tense with the ladder;
`SKILLS.md`'s "Sharing below the node level" gains the grouping lesson (10.2's
`saved > 0`, and why `marginal <= budget` was the wrong test).

**Pinned oracles: none are expected to move.** The line map and the shape hash
are both taken from the single-root `everyNode` fixture, and grouping only
partitions *between* outputs; `DEFAULTS` still renders to nothing in the hash.
If either moves, that is a finding to explain in 7.6, not a re-pin to wave past.

### 10.6 Predictions, registered before the ladder runs

1. The 2/3/4-field ratios under B2's rule reproduce 7.2 and 7.4 within run
   noise - **1.29x/1.57x/1.80x** at AVX-512, 1.31x/1.47x/1.67x at 128-bit -
   because the rule emits the same bytes `withGroupBudget(200)` did for these
   shapes. Confidence: high; this is a consistency check, not a measurement.
2. The gain keeps growing past four: eight outputs over one date beat their
   unshared shape by **at least 2.0x** at AVX-512 and at least 1.5x at 128-bit,
   for the reason 7.2 gave (more outputs amortise the same fixed per-lane-group
   cost). Confidence: medium - `add_months` tails are heavier than field tails,
   and 128-bit register pressure has not been measured past four live outputs.
3. Compile time stays under one second through the 12-output row at AVX-512,
   extrapolating 7.5's ~1.4 ms per op (272 ms at 200 ops); `fusedCeiling` ships
   at 400. Confidence: medium-low - this is the prediction most likely to set the
   ceiling lower, and 7.5's own outlier (2.4 s for twenty *separate* methods)
   says compile cost does not only scale with ops in one method.
4. No non-calendar committed number moves, asserted by the byte-identity test in
   10.5 rather than by re-measurement. Confidence: high.
5. No pinned oracle moves. Confidence: high (10.5 says why).
6. **For task 45, not for this task:** once the null-free validity fast path
   lands, B2's ratios *rise*, not fall. The validity write is paid once per
   output per lane group under both shapes, so removing it takes the same
   absolute cost from both sides of the ratio, and the shared side is the smaller
   one. Registered here so that whoever re-measures after 45 has a direction to
   score, since 7.2's "the win grows with field count" could be misread as "the
   win is the fixed cost" - it is not; it is the arithmetic, and 45 will make that
   plainer.

### 10.7 Risks

1. **Clause 2 leaks.** The whole design rests on `saved > 0` admitting fragment
   reuse and nothing else. A future node that reports a fragment key it does not
   actually reuse work through (or a `FragmentKind` whose "prefix" is small) would
   widen methods for no saving. The non-calendar byte-identity test catches the
   shapes that exist; the rule's javadoc has to say what `saved` means so the next
   `FragmentKind` keeps it honest.
2. **The ordering limitation bites a real query.** `year(d), year(d2), month(d)`
   pays a prefix it need not. Probably rare - date columns in a projection are
   usually adjacent - but the corpus does not say. Pinned as a limitation;
   reordering stays in the debt register until a shape asks for it.
3. **Weights counted from the wrong emitter.** 10.3's constants depend on which of
   #73 (guard removal) and #64 (`dayofyear`) have landed. Sequence B2 after both;
   if it cannot wait, the constants are re-counted in the merge and 7.6 says so.
4. **The ceiling is set by compile time at 128-bit rather than AVX-512.** 10.4
   measures both, and the ladder's rule (i) requires both widths to win, so a
   128-bit register-pressure cliff past four outputs would cap `fusedCeiling` for
   both widths. If that happens, a width-dependent default - the shape 2.20's task
   50 discussion already anticipated for `shareChronoPrefix` - is the follow-up,
   recorded rather than built here.
5. **Task 43 is adjacent and stays open.** Clause 2 bounds a *multi-output*
   method by `fusedCeiling`; a single output holding several calendar nodes
   (`CASE WHEN ... THEN year(d) ELSE month(d)`) is still unbounded and still task
   43's question. B2's ladder gives 43 more data points on the same axis, and
   `fusedCeiling` is a number 43 can reuse, but B2 does not touch `fitsBudgets`.

### 10.8 Sequencing

Off `master` once #73 and #64 have landed (10.7 risk 3), one branch, four
commits, each green on the standing gate (both widths, both modules,
`dev/lint-java`, `dev/scalastyle`, `catalyst/doc`):

1. **The honest weights** (10.3) and `fusedCeiling` as an option, with the rule
   *not yet wired in*: `weightOf` restated as prefix plus tail, constants
   re-counted from emitted instructions, `FUSED_CEILING` present with its
   candidate value. No emitted byte changes; no test changes.
2. **The rule** (10.2), behind `shareChronoPrefix`: `addOps` returns `saved`,
   `groupOutputs` gains clause 2, the four tests in 10.5 rewritten or added. This
   is the commit where the byte-identity guard for non-calendar shapes has to be
   green before anything else is looked at.
3. **The ladder** (10.4): the benchmark section, three runs at each width, the
   `-XX:+PrintCompilation` timings, and `fusedCeiling`'s value chosen and set.
   Section 7.6 written with the tables and predictions 1-5 scored.
4. **The default is live, and the record swept**: the parity file regenerated
   once, docs and README requoted from that run, milestone row 32 to DONE, 2.9's
   closing paragraph and the debt entry rewritten in the past tense, `SKILLS.md`
   updated.

Commit 1 could be folded into 2; it is kept apart so that the byte-identity
claim in commit 1 ("no emitted byte changes") is testable on its own, and a
mistake in a re-counted constant shows up as a grouping change in commit 2 rather
than as an unexplained regeneration diff in commit 4.

### 10.9 Explicitly out of B2

* Output reordering for fragment affinity (10.2's last row; debt register).
* Task 43's single-output bound, task 44's epilogue bound, and any change to
  `GROUP_BUDGET` itself.
* A width-dependent `fusedCeiling` or `shareChronoPrefix` default (10.7 risk 4).
* Any change to the fragment mechanism, the guard, or `emitChronoPrefixOnce`;
  B2 changes which outputs share a method, never what a method emits.
* An end-to-end (`VarkaThroughputBenchmark`) four-field case. Worth having for
  the docs' headline numbers, but it prices the evaluator and Arrow path as much
  as B2, and the parity harness is where this task's claims are made; if added, it
  is added as its own committed case and quoted as an end-to-end number.
