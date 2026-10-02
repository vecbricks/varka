# Task 223: a shared slot decided by the body's own use count

## 1. Where this came from

Row 223 of `PLAN_MILESTONE_6.md`, from `PLAN_TASK_191.md` 3.2 (27 September 2026): `Slots.plan`
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
body's node set is the one task 191 introduced, so a node under a date whose materialized prefix the
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
- `emitted_bytes.json`, the price tables and `emit_cost_audit.json`, regenerated as task 235 showed
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

A walk that visits a node more often than its parent edges say would load an unset local; the
emitter suites and the fuzzer run every body, and the new test states the count's property.

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

Each family loses exactly the shared slots section 2 counted as used once, at three bytes each in
the int families and a little under in the wide long one, where more of the removed slots had an
index under four and a one-byte store.

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
4. **Held.** Every coverage row is byte-identical; the `fuzz` and `fuzz_long` blocks moved, as
   they had to, since a block of 100 shapes holds kernels of several groups, and so did the
   `option_arms` digests, which hash every shape.

**The audit, requoted.** No conclusion of `PLAN_TASK_199.md` 9 moves: the refitted prices' error at
2000 bytes and over is 2.2% at the median and 11.9% at the 99th percentile, against 12.4% before.
The methods of 8000 bytes and over number 139, against 161: the removed stores take 22 of them
under the line.

### 9.3 What the count does not see, and a finding beyond the row

**Shared slots still stored and never read: 5708 over the corpus's loop and epilogue methods.** A
calendar node reaches its date through the shared prefix (`shareChronoPrefix`), so two calendar
nodes over one date visit it once, and the count, which counts each node's edge, says twice. The
error is harmless either way - a count too high costs a dead store, one too low a recomputation,
never an answer - and it is two in a hundred of what the change removed; counting the prefix's
visit instead would mean restating the prefix's rule in the count.

**Other reference locals stored and never read**, counted from the emitted bytecode of the same
corpus after the change: 66,024 memory segments built with `ofAddress`, 59,392 of them in the masked
methods - the one example read, a dense loop method, builds output 0's validity segment, which a
dense loop never writes - 50,020 column vectors loaded with `fromMemorySegment` into a local nothing
reads, and 145 masks from `indexInRange`. They are not the memo's - no `dup` precedes their stores.
The segments are step (3) of `VarkaBodyEmitter`'s prologue, which builds every output's validity
segment in every body although, since task 70, the bitmap pass or a fill-once writes most outputs'
validity; the column loads are task 198's materialized prefix, which a consuming group reloads
whole, every lane group, where only the month vector is loaded on demand and a field reads a subset
(`month` and `quarter` one vector of six). At about ten bytes a segment and twenty a reload, they
come to some 3% of the corpus's loop and epilogue bytes, four times what this task saved; the column
loads may also cost run time, since a vector load with a bounds check is not plainly dead to C2.
Proposed as a row of its own; the owner placed it in this milestone the same day, as row 239.
