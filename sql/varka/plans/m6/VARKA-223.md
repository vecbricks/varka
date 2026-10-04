# VARKA-223: a shared slot decided by the body's own use count

## 1. Where this came from

Row 223 of `m6/PLAN.md`, from `VARKA-191.md` 3.2 (27 September 2026): `Slots.plan`
gives a node a shared slot when `analysis.useCount`, the kernel's count, is above one, so a node
shared across two groups but used once inside a body still gets a slot in that body's frame. It is
one of the size-control rows the owner made this milestone's goal on 30 September, and was picked
on 2 October 2026 as the next row to resolve before milestone 6 closes.

## 2. The admission check, done

**What a single-use shared slot costs.** `VarkaVectorWalk.emitValue` computes a shared node at its
first visit, then `dup` and `astore` it into its slot; a later visit loads it. A node used once in
its body has no later visit, so the slot costs a `dup`, an `astore` - three bytes once the slot
index is past three - and a local, and nothing reads it. The row's "a store and a reload" is one
too many: there is no reload. C2 removes a dead store to a local, so the gain is bytecode bytes,
which is what the byte budget counts, and the interpreter's and C1's work before C2.

**How common it is.** A temporary tally in `Slots.plan` counted, over every group body of the cost
corpus (`VarkaEmitCostCorpus`) under the defaults, the shared slots and those whose node the body
uses once - once per parent edge inside the body and once per output root it serves:

| family | group bodies | shared slots | used once in the body |
|:--|--:|--:|--:|
| fuzz, int lane | 5842 | 5394 | 2136 |
| fuzz, long lane | 4276 | 5994 | 896 |
| wide, int lane | 36432 | 86108 | 66260 |
| wide, long lane | 32244 | 147500 | 96486 |
| make_date ladder | 56 | 120 | 16 |
| size ladder | 772 | 1544 | 0 |
| cheap tails | 40 | 40 | 0 |

Common, by the row's test: two in five of the fuzz int lane's shared slots, three in four of the
wide int lane's. Most are column loads - a column three outputs read counts three kernel-wide and
once in the body of a group that holds one of them. The fuzz shapes reach it because a calendar
output is heavy enough to take a group of its own. The size ladder has none: its outputs are all
over one date, which every group's body reads several times.

**The cost corpus before the change** (bytes are machine-independent, counted from the emitted
classes):

| family | shapes | builds | loop and epilogue bytes | dense loop methods |
|:--|--:|--:|--:|--:|
| fuzz, int lane | 1000 | 1000 | 4424463 | 1310 |
| fuzz, long lane | 1000 | 1000 | 1928497 | 781 |
| wide, int lane | 200 | 205 | 29686389 | 104 |
| wide, long lane | 200 | 259 | 17059400 | 0 |
| make_date ladder | 2 | 2 | 172040 | 14 |
| size ladder | 5 | 5 | 2602528 | 193 |
| cheap tails | 2 | 5 | 26092 | 6 |

## 3. The design

### 3.1 The body's own use count

`Slots.plan` counts, once per body, how often the body uses each node: once per parent edge from a
node the body emits, and once per output root it serves. That is the count the walk's `computed`
set sees, since within a body a shared parent is computed once and loaded after, so its children are
visited once for it. A node gets a shared slot when that count, not the kernel's, is above one. The
body's node set is the one VARKA-191 introduced, so a node under a date whose materialized prefix the
body loads, and does not visit, uses nothing.

### 3.2 What is deliberately unchanged

The kernel-wide `useCount`, which `Analysis` keeps as its memo and its first-visit order; the walk;
the prefix fragments; the grouping, which the weights decide and no measured byte enters unless a
group measures over the budget.

### 3.3 Registered op counts

No Vector API call moves: only a `dup` and an `astore` per single-use shared node, and the locals'
numbering.

## 4. Files

- `Slots.java`: the per-body count and the slot decision on it.
- A test that every shared slot of every body in the cost corpus serves at least two uses.
- `emitted_bytes.json`, the price tables and `emit_cost_audit.json`, regenerated as VARKA-235 showed
  they must be when the emitted bytes move.

## 5. Tests, and what each is for

- The new test: the property the change states, over the cost corpus.
- `VarkaIrFuzzSuite` and the emitter suites: the answers do not change.
- `VarkaEmittedBytesSuite`: the diff reviewed, every moved method one with a single-use shared slot.

## 6. The measurement

The cost corpus again after the change, from the same tally: bytes, loop methods and builds.

### 6.1 Predictions, registered before the run

1. Loop and epilogue bytes fall by between 0.2% and 1% in the two wide families, by about three
   bytes per removed slot, and by less in the fuzz families; the size ladder and the cheap tails do
   not change at all, since they have no such slot.
2. No answer changes, and no Vector API call site moves, so the call-site budget decides as before.
3. Dense loop methods are unchanged in every family; builds fall in the wide long family, where a
   group that measured just over the byte budget now measures under it, or stay the same.
4. `emitted_bytes.json` moves only for kernels of several groups; every coverage row, a single
   expression in one group, is byte-identical.

## 7. Risks

A count that disagrees with the walk is never a wrong answer. Too high, it leaves a slot nothing
reads, a `dup` and a store; too low, the walk visits the node again and computes it again, since
`emitValue` loads a slot only where one is planned - more bytes, and a guard or word store done
twice. The emitter suites and the fuzzer run every body, and the new test states the count's
property. *Corrected after the review (9.4, item 7): this section first said a count too low would
load an unset local.*

## 8. Sequencing

One pull request: the plan, the change, the test, the regenerated files, and row 223 marked done.

## 9. Outcome

Done on 2 October 2026. `Slots.plan` counts each node's uses inside the body and gives it a shared
slot only when that count is above one; `VarkaEmitterArithmeticSuite` gains a test that two heavy
outputs over one column, a group each, park the column nowhere, and that two light outputs in one
group still share it - and the test fails, naming a parked slot in each of the eight bodies, with
the kernel-wide count restored.

### 9.1 The cost corpus after the change

| family | shared slots | loop and epilogue bytes | change | builds | dense loop methods |
|:--|:--|:--|--:|:--|:--|
| fuzz, int lane | 5394 -> 3258 | 4424463 -> 4418073 | -0.14% | 1000, unchanged | 1310, unchanged |
| fuzz, long lane | 5994 -> 5098 | 1928497 -> 1925821 | -0.14% | 1000, unchanged | 781, unchanged |
| wide, int lane | 86108 -> 19848 | 29686389 -> 29490987 | -0.66% | 205, unchanged | 104, unchanged |
| wide, long lane | 147500 -> 51014 | 17059400 -> 16832156 | -1.33% | 259, unchanged | 0, unchanged |
| make_date ladder | 120 -> 104 | 172040 -> 171992 | 48 bytes | 2, unchanged | 14, unchanged |
| size ladder | 1544, unchanged | 2602528, unchanged | 0 | 5, unchanged | 193, unchanged |
| cheap tails | 40, unchanged | 26092, unchanged | 0 | 5, unchanged | 6, unchanged |

The table was counted before VARKA-235 gave the long draws a `NarrowLane` root, so its two long-lane
rows describe the corpus as it was then; the int-lane rows and the ladders are unchanged by it. Each
family loses exactly the shared slots section 2 counted as used once, at three bytes each in the int
families and a little under in the wide long one, where more of the removed slots had an index under
four and a one-byte store.

### 9.2 The predictions, scored

1. **Held but for one family.** The wide int family's bytes fall 0.66%, inside the 0.2% to 1%
   predicted; the wide long family's 1.33%, outside it, its methods being smaller and its
   single-use slots more of them; the fuzz families fall 0.14%, and the size ladder and the cheap
   tails do not change.
2. **Held.** The emitter suites and the fuzzer pass; no Vector API call moves, only a `dup` and an
   `astore` per slot removed.
3. **Held, by its second clause.** Builds and dense loop methods are unchanged in every family: no
   group of the wide long family measured close enough over the budget for three bytes a slot to
   bring it under.
4. **Held for the first build, not after the review.** Every coverage row was byte-identical; the
   `fuzz` and `fuzz_long` blocks moved, as they had to, since a block of 100 shapes holds kernels
   of several groups, and so did the `option_arms` digests, which hash every shape. *Corrected
   after the review (9.4): its count moves five coverage rows at both widths, single-group kernels
   the first build's count gave a slot read once - `make_ym_interval(year(d), month(d))` and the
   same times two, `year(d) = 2021 OR month(d) = 3` (a date two calendar fields reach through one
   prefix), and `coalesce(d, d2)` and `if(l IS NULL, l2, l)` (a column an `isNotNull` reads beside
   its value). The prediction was that only kernels of several groups move; a single-group
   kernel moves too wherever the first count was wrong.*

**The audit, requoted.** No conclusion of `VARKA-199.md` 9 moves: the refitted prices' error at
2000 bytes and over is 2.2% at the median and 11.9% at the 99th percentile, against 12.4% before.
The methods of 8000 bytes and over number 155, against 169 on master since VARKA-235 widened the
long corpus (139 against 161 before it): the removed stores take 14 of them under the line.

### 9.3 What the count does not see, and a finding beyond the row

**Shared slots still stored and never read: 5708 over the corpus's loop and epilogue methods.** A
calendar node reaches its date through the shared prefix (`shareChronoPrefix`), so two calendar
nodes over one date visit it once, and the count, which counts each node's edge, says twice. The
error is harmless either way - a count too high costs a dead store, one too low a recomputation,
never an answer - and it is two in a hundred of what the change removed; counting the prefix's
visit instead would mean restating the prefix's rule in the count. *The review removed all of them
by having the count ask the prefix's rule rather than restate it (9.4).*

**Other reference locals stored and never read**, counted from the emitted bytecode of the same
corpus after the change: 66,024 memory segments built with `ofAddress`, 59,392 of them in the masked
methods - the one example read, a dense loop method, builds output 0's validity segment, which a
dense loop never writes - 50,020 column vectors loaded with `fromMemorySegment` into a local nothing
reads, and 145 masks from `indexInRange`. They are not the memo's - no `dup` precedes their stores.
The segments are step (3) of `VarkaBodyEmitter`'s prologue, which builds every output's validity
segment in every body although, since VARKA-70, the bitmap pass or a fill-once writes most outputs'
validity; the column loads are VARKA-198's materialized prefix, which a consuming group reloads
whole, every lane group, where only the month vector is loaded on demand and a field reads a subset
(`month` and `quarter` one vector of six). At about ten bytes a segment and twenty a reload, they
come to some 3% of the corpus's loop and epilogue bytes, four times what this task saved; the column
loads may also cost run time, since a vector load with a bounds check is not plainly dead to C2.
Proposed as a row of its own; the owner placed it in this milestone the same day, as row 239.

### 9.4 The review, 2 October 2026

A code review of the pull request found nine problems, none of which changes an answer. All nine
are addressed.

1. **The count followed an edge the walk does not.** It counted a calendar node's edge to a date
   whose materialized prefix the body loads and does not visit, which 3.1 had said uses nothing.
   A date reached by another path as well therefore got a slot it reads once.
2. **The count added one visit per calendar node where the prefix visits its date once per
   fragment.** These were the 5708 slots of 9.3, which that section recorded as the count's own
   error and left. Items 1 and 2 are one defect: the count restated what the walk visits instead
   of asking the walk's rule. `Slots.bodyUses` now asks it. A date edge counts once per prefix
   fragment's key, and not at all where the body loads the date's prefix without visiting the date
   (`Slots.visitsLoadedDate`, which `emitChronoPrefixOnce` asks too). The key has to be known
   before any slot is numbered, so its word is now the word's owner in the kernel's word algebra,
   or a marker for a word the body never reads. That tells words apart exactly as their slots do,
   which `assertWordAlgebraAgrees` already holds the slots to. The test of item 3 found a third
   edge of the same kind: an `IsNotNull` reads its column's validity word and never its vector,
   and its edge no longer counts either.
3. **The test 4 and 5 promised was missing.** `VarkaUnreadLocalsSuite` now holds every loop and
   epilogue method of the cost corpus to no shared slot that nothing reads, reading the bytecode
   through `VarkaUnreadLocals`. Before item 2 it found 5,722 such slots. With items 1 and 2 it found
   396, every one a column that an `isNotNull` read beside a value. With the third edge it finds
   none.
4. **The count ran where nothing reads it**: in the driver, which plans every output and walks no
   vector, and with CSE off. It now runs only for a loop or epilogue body under CSE.
5. **Two walks had to agree on which edges count.** The count is one function beside
   `emittedNodes`, asking the `loadedPrefixDate` and the visit rule the emission asks, rather than
   a loop restating them.
6. **`Analysis.useCount` kept counts that nothing read.** It is now `analyzed`, a set, which is
   the memo it had become.
7. **Section 7 said a count too low would load an unset local.** It recomputes the node instead,
   since `emitValue` loads a slot only where one is planned. Corrected there.
8. **The pull request's description quoted the figure from before VARKA-235** for the methods of
   8000 bytes and over. Requoted.
9. **A qualified `VarkaVectorIR.childrenOf`** in a file that imports it statically, gone with the
   rewrite of item 5.

A second review, of #553 stacked on this one, found six more here. A count too low was still
unguarded: the walk would recompute a node's subtree with no sign of it. `emitValue` now refuses a
second visit of a node without a shared slot under CSE, and no suite meets the refusal.
`fragmentKey` takes the `Analysis` rather than a copy of its word algebra kept on `Slots`. The
loop and epilogue of a group share one count (`Analysis.bodyUses`, cleared with each grouping's
materialized prefixes) where each made its own. The arithmetic test reads its slots through
`VarkaUnreadLocals` instead of parsing disassembly. Prediction 4 of 9.2 had kept its "Held" after
five coverage rows moved, and row 223 garbled its figures; both are corrected.

Over the corpus of 9.1, no loop or epilogue method grew under the review's change, which is what
a count too low would have caused, and 2,692 of 36,483 shrank. The loop and epilogue bytes fell by
0.039% in the fuzz int family, 0.050% in the wide int family, and less elsewhere. The emitted-bytes
oracle, the price tables and the cost audit are regenerated on it, and no conclusion of 9.2
moves: the methods of 8000 bytes and over still number 155, and the fitted prices' error at 2000
bytes and over is still 2.2% at the median and 11.9% at the 99th percentile. The shared
crossings `VarkaEmitterBudgetSuite` pins move later, since the four fields over a date no longer
keep the date a slot the shared prefix reads once: with the bitmap pass the single epilogue now
crosses `HugeMethodLimit` at 51 outputs rather than 49, and without it at 45 rather than 44. The
unshared crossings stay where they were.
