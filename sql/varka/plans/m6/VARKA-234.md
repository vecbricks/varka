# VARKA-234: A shared calendar prefix over a guarded column fails the emitter's word check

*Planned 30 September 2026 (milestone 6 row 234), with its admission check run the same night.*

## 1. Where this came from

The fuzz campaign of the night of 29 to 30 September 2026 ran twenty workers on the fork for five
hours: the IR fuzzer at 200,000 iterations a run, the same with the long lane's column bound raised,
and the coverage-composition fuzzer. The composition fuzzer passed all 152 of its runs. The IR
fuzzer failed 110 of its 147 runs and the raised-bound variant 42 of its 58, every one of them in
the int-lane test and every one on the same emitter self-check,
`VarkaBodyEmitter.assertWordsLive`: "word slot N is stored but never loaded in a LOOP body". The
campaign's logs are not committed; the shapes below are, as tests. The owner asked for the root
cause, then for this task to fix it.

## 2. The admission check, done

**The first failing shape.** Seed 2026093000000, iteration 64564, three outputs over column 1:
`(divc:100 (dayOfMonth (guardedDay col:1)))`, `(month (dayOfMonth (guardedDay col:1)))` and
`(quarter (addDays (year col:1) col:1))`, under options the fuzzer drew, with the masked path
forced. The suite did not name it: `emitOrSkip` reports its context for a rejection and a decline
but not for an `IllegalStateException`, so the iteration came from a local patch that does.

**Minimized by a scratch test.** Flipping each option the fuzzer drew back to its default leaves
the failure, except `shareWholeNodes`; the third output is not needed; neither is the division.
The smallest failing pair is two outputs sharing a guarded date's decomposition in different
loop-method groups:

| shape | defaults | `shareWholeNodes` off |
|---|---|---|
| `dayOfMonth(gd(c1))`, `month(dayOfMonth(gd(c1)))` | emits | fails |
| `dayOfMonth(c1) / 100`, `month(dayOfMonth(c1))`, no guard | emits | emits |
| `year(gd(c1))`, `lastDay(c0)`, `month(gd(c1))` | fails | fails |
| `dayOfMonth(gd(c1 + c2))`, `lastDay(c0)`, `month(dayOfMonth(gd(c1 + c2)))` | emits | emits |

The third row fails under every default: `lastDay(c0)` between the two outputs puts them in
different groups, and whole-node sharing cannot join them across it.

**The trigger is VARKA-198's materialized prefix.** On the failing pair, turning
`materializeChronoPrefix` off, setting `methodByteBudget` to 0, or turning `shareChronoPrefix` off
each makes the shape emit; turning `groupLocalSlots` off or `guardDayProducers` on does not.

**The mechanism.** Under `materializeChronoPrefix` the first group that decomposes a date stores
the prefix in the caller's scratch, and every later group loads it. A later group does not emit the
date underneath (`VarkaChronoLowering.emitChronoPrefixOnce`) unless the date's validity word is
owned by a node under it, in which case the date is visited for that word and its value dropped.
The range check of a `GuardedDay` over that date is therefore not emitted in the later group, which
is correct: the producing group ran the same check over the same lane groups. But
`Slots.liveWords` walks every node of the group's trees, and at the `GuardedDay` it demands the
date's word for the check. When that word is an input's, as it is over a bare column, the input's
word is live, the lane group's prologue stores it, and nothing in the body loads it.

**Not VARKA-148.** With the int-lane division weighed at 1 again, the first failing shape fails the
same way, and the smallest pair has no division.

**Since when.** Commit `defe20108c4` (VARKA-198, VARKA-29 September 2026) added both the materialization
and the later group's skip; the default flipped in the same task.

**What a query sees.** No wrong answer: the planner logs the emitter's failure once per JVM and
admits the shape, and the executor meets the same failure behind the ghost fallback and runs the
row path. The compiler places a `GuardedDay` only over runtime day arithmetic near the calendar's
range edges (`VarkaChronoCompiler`), where the guarded date's word is usually its own, which emits;
`dev/varka_emit.sh` on `year(date_add(d, 7))`, `last_day(d2)`, `month(date_add(d, 7))` and on the
same with `date_add(d, i)` compiles no guard at all. The fuzzer met the failure about once in
150,000 int-lane shapes, which the CI's 300 iterations and the nightly's 10,000 would rarely reach.

**What the check would have rejected:** relaxing `assertWordsLive`, which is doing its job - a
word stored and never read is per-group work the liveness pass exists to remove; and emitting the
date in every later group, which would put back the range check and the column's load that the
materialization saves.

## 3. The design

### 3.1 The walk follows the emission, and both read one decision

* **`Slots.liveWords` takes the body's group** and does not descend from a calendar node into its
  date when the group loads that date's materialized prefix from another group. The date is
  deferred. Once the walk and the propagation reach their fixpoint, a deferred date whose word is
  its own and live is walked, because the lowering visits exactly such a date for its word; the
  walk and the propagation then run again, until no deferred date turns live.
* **The dates a body visits are recorded in `Slots`** (`visitedMaterializedDates`), and
  `emitChronoPrefixOnce` reads the record instead of recomputing its rule from the slots. With the
  liveness pass off every own word is live, so the planner records every such date whose word is
  its own, which is the rule the lowering applied; a dense body has no words and visits none.
* **`VarkaIrFuzzSuite.emitOrSkip` reports its context** when the emitter's own check fails, as it
  already does for a rejection, so the next failure of this kind names its iteration.

No option switch: this is an emit-time invariant, and the form it replaces throws.

### 3.2 What is deliberately unchanged

* Which dates a later group visits: the record states the lowering's rule, it does not change it.
* Slot allocation: the topological order and the body's node set still include a date the group
  does not emit, so every slot number, and every emitted byte of a shape that emits today, stays.
* The materialization plan (`Analysis.planMaterialized`) and `assertWordsLive` itself.

### 3.3 Registered op counts

None change: no lowering is touched.

## 4. Files

| file | what |
|---|---|
| `Slots.java` | the walk's deferral and fixpoint; `visitedMaterializedDates` |
| `VarkaChronoLowering.java` | `emitChronoPrefixOnce` reads the record |
| `VarkaEmitterValiditySuite.scala` | the regression shapes, emitted and run |
| `VarkaIrFuzzSuite.scala` | `emitOrSkip` names the iteration on an emitter self-check |
| `m6/PLAN.md` | row 234 |

## 5. Tests, and what each is for

* **The shapes of section 2 emit, and answer as the reference evaluator does** on both bodies at
  both widths: the failing pair under `shareWholeNodes` off, the three-output shape under the
  defaults, each with the bitmap pass on and off. They are the two ways the old walk and the
  lowering disagreed, and the answers check that skipping the later group's range check lost
  nothing.
* **A guard over a date whose word is its own is still visited** in the later group, and answers:
  the `c1 + c2` row, which the record must keep emitting as before.
* **The first failing fuzz shape replays clean**: seed 2026093000000, iteration 64564.
* **`emitted_bytes.json` is unchanged**, which is the claim that no shape emitting today moves.

## 6. The measurement

No benchmark: the task makes no speed claim. The verification is the fuzzer: the night's failing
seeds replayed at 200,000 iterations, and fresh seeds beside them.

### 6.1 Predictions, registered before the run

1. **`emitted_bytes.json` does not move.** A word the old walk kept alive with no emitted reader
   always failed the check, so no shape the oracle pins can reach the change.
2. **Every failing seed of the night replays clean**, and so do fresh seeds at the same count.
3. **No existing test moves**, since the record only restates the lowering's rule.

## 7. Risks

1. **The fixpoint could stop early.** Walking a deferred date may demand a word that makes another
   deferred date live; the loop runs until a round adds nothing, and the tests include a date
   visited through its own word.
2. **A date reached by another path is still walked.** An output in the same group that reads the
   date outside a calendar node emits it, and the walk reaches it through that node; `reached`
   keeps a node walked once, whichever path came first.
3. **The record and the lowering drift apart again.** They cannot: the lowering reads the record.

## 8. Sequencing

1. This plan, with row 234 marked Planned.
2. The fix, the tests, the fuzz suite's report, the fuzz replay and section 9, in one commit.

## 9. Outcome

### 9.1 Built, 30 September 2026

`Slots.liveWords` walks a body through a `WordWalk` that defers a date a calendar node reaches when
the group loads that date's materialized prefix. At the end each deferred date is walked on a copy,
kept when its own word comes out live and dropped otherwise, in rounds until one keeps nothing. The
kept dates, and any such date another node reaches whose own word is live, are recorded in
`Slots.visitedMaterializedDates`; with the bitmap pass off the planner records every such date whose
word is its own. `emitChronoPrefixOnce` reads the record. `VarkaIrFuzzSuite` names the iteration of
an emitter self-check. `VarkaEmitterValiditySuite` gains the regression test - the three shapes of
section 5, the bitmap pass on and off, 4 and 16 lanes, both bodies against the reference evaluator,
each asserted to emit more than one loop method - which fails on the unfixed emitter with the word
check's message and passes with the fix.

**A correction to 3.1, made while building.** It said a deferred date is walked once "its word is
its own and live". Liveness measured without the date's subtree is not enough: in a shape that
emits today the guard over such a date can be what makes its word live, and the lowering visits the
date because of it, so a walk that never looked inside would stop visiting it and move bytes. The
trial walk therefore counts the date's own subtree, which reproduces the decision the old walk and
the lowering reached wherever they agreed.

### 9.2 The predictions, scored

1. **Held.** `emitted_bytes.json` did not move: `VarkaEmittedBytesSuite` passes against the
   committed file.
2. **Held.** All 154 seeds that failed during the night, and 20 fresh ones, replay clean at 200,000
   iterations of the int-lane test, the one every failure was in: 174 runs of 174.
3. **Held.** No existing test moved: every catalyst Varka suite passes, 460 tests, with the 27
   opt-in ones cancelled as always.

### 9.3 What the task leaves

The same night found two problems outside this task, both from VARKA-226's per-test watchdog: the
nightly's sweep step hangs, because the ten-minute cap halts the exhaustive multiply-high sweep
(13 minutes 26 seconds on 26 September), and sbt then waits on the halted fork instead of failing.
They are reported to the owner for rows of their own.

### 9.4 The review, 30 September 2026

The owner ran `/code-review high` on the pull request and asked for every finding to be addressed.
Nine findings; what each changed:

1. **The trial kept a guarded date whose word is its own, so its range check ran again in every
   group that loads the prefix.** 9.1's rule counted the date's own guard as a reader of its word.
   The rule is now the one the old comment stated: a loaded date is visited only when something
   the body emits outside it reads its word - a tail's root write, a comparison above the tails -
   and the walk enters it only once that is so, in rounds. No trial copies are made, which also
   answers finding 5. This moves bytes, which 3.2 had kept on purpose; the owner's request
   overrides that choice.
2. **The deferral matched every child identical to the date.** Both walks now defer only the
   date's own position, so an operand that is the same node - an `add_months` whose count is its
   date - is walked, as the lowering emits it.
3. **Under `misdescribeWordLiveness` the record followed the true liveness while the slots followed
   the inverted one.** The record is now taken on the verdict the slots are planned from.
4. **Slot planning still counted nodes under a skipped date.** `Slots.plan` now decides the body's
   emitted node set first - its trees less the subtree of each loaded date it does not visit - and
   takes the guard flags, the per-node scratch locals, the `Cond` slots and the literals from it.
   A column only such a subtree reads keeps its input slots, since the word algebra tells inputs
   apart by slot number, but when its word is dead the body no longer sets up its segment
   (`Slots.skippedColumns`).
5. **The trial copied the whole walk per date.** Gone with finding 1.
6. **The bitmap-off path walked every body.** The emitted set is computed only when the kernel
   materializes a prefix, by one walk, and the bitmap-off record is read off it.
7. **The lowering restated "this group loads the prefix".** It asks `Slots.loadedPrefixDate`, as
   both planning walks do.
8. **The regression test did not prove a prefix was materialized.** It sets
   `materializeChronoPrefix` explicitly and asserts the kernel asks for scratch.
9. **New comments cited bare task numbers.** They point to `VARKA-198.md` instead.

**What moved.** The regenerated `emitted_bytes.json` moves 59 of the 100 int-lane fuzz blocks at
each width and the option-arm digests, and no coverage row and no long-lane block. A per-shape dump
of the 10000 int-lane shapes against master's names 100 moved shapes, the same at both widths, each
with a group that loads a materialized prefix. None grew: their loop methods lost 0 to 20 bytes,
median 12, the column segment such a group no longer sets up, and the rest of the change is locals
renumbered or an epilogue's. One existing test pinned an incidental fact the change moves: under a
300-byte budget, `VarkaEmitterBudgetSuite`'s declined 22-tail shape now names one output where it
named all 22, since the groups that load the prefix read no column and fit; it still names what the
budget-off emitter names, which is the test's point, and now asserts the one.

**The predictions, rescored for the final form.** Prediction 1 held for the first version and does
not for the final one: the bytes move, as finding 1 and 4 intend. Predictions 2 and 3 are rechecked
on the final form below. On the final form all 154 failing
seeds and the 20 fresh ones replay clean again at 200,000 iterations, 174 runs of 174, and every
catalyst Varka suite passes, 460 tests.
