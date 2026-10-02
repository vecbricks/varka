# Task 235: fuzz the `NarrowLane` root

## 1. Where this came from

Row 235 of `PLAN_MILESTONE_6.md`, found by the code review of #520 (task 199) on 30 September
2026: the cost model's coverage test trusted the grammar's reach, and `NarrowLane` had no price.
The price was added in #520; the reach was not. The IR fuzzer never draws a `NarrowLane`, so the
narrowing every `TIME` kernel ends in (`VarkaTimeCompiler` builds it at the root of each one) is
checked against the reference evaluator only on the emitter suite's fixed shapes. Picked on
2 October 2026 as the first of the rows to resolve before milestone 6 closes.

## 2. The admission check, done

- **The gap is real.** `VarkaIrFuzzSuite`'s two reach tests subtract `"NarrowLane"` from the set
  they assert, and neither `VarkaIrGrammar.Shapes` nor `LongShapes` has an arm that builds it.
- **The oracle exists.** `VarkaReferenceEvaluator.evalLong` answers a `NarrowLane` with its
  child's full 64-bit value, and leaves the narrowing to the suite, so a value that did not fit
  32 bits would show as a difference rather than be truncated on both sides.
- **A drawn kernel may mix it with plain roots.** `VarkaLoopEmitter` requires every output of a
  kernel to share one emission lane (`VarkaVectorIR.emissionLane`), and a narrowing root's
  emission lane is its child's, the long lane; so a narrowing root and a plain long root can be
  outputs of one kernel, the case no fixed shape covers.
- **Only one of the two exclusions can go.** The row says the name is dropped from both reach
  tests, but `NarrowLane` takes a long child, nothing in the IR widens an int lane, and the int
  grammar builds int trees only: no int shape can hold one. The long-lane test drops it; the int
  test keeps it, with that reason in place of the root-only one.
- **The corpus moves only where it must.** Every shape draws from its own `Random`
  (`shapeRandom(seed, k)`), so an arm that consumes randomness only when a root's bound fits 32
  bits changes those shapes and no other.

## 3. The design

### 3.1 A root arm in `LongShapes`

`LongShapes.root(depth)` draws a value tree as before and, when its magnitude bound fits an int,
wraps it in a `NarrowLane` one time in three - the narrowing is a truncation with no overflow
check, so it is built, as the compiler builds it, only over values proven to fit. The narrowing
is a root-only node, so it is drawn here and never inside `value`. `drawLongShape`,
`drawWideLongShape` and the long-lane reach test draw their value roots through it.

The long-lane differential test reads a narrowing root's output as four bytes a row, the store's
width, and compares it, sign-extended, with the evaluator's 64-bit answer.

### 3.2 What is deliberately unchanged

The emitter, the reference evaluator, the cost model's prices, and the int grammar.

### 3.3 Registered op counts

None: the emitted code does not change; the corpus over it does.

## 4. Files

- `VarkaIrGrammar.scala`: `LongShapes.root`, and the two long draws through it.
- `VarkaIrFuzzSuite.scala`: the long reach test through `root` with the exclusion dropped, the int
  test's reason corrected, and the four-byte read in `runOneLong`.
- `emitted_bytes.json` and `emit_cost_audit.json`, regenerated (`VARKA_BYTES_REGEN=true`,
  `VARKA_COST_REGEN=true`), since both pin shapes the long grammar draws.

## 5. Tests, and what each is for

- `VarkaIrFuzzSuite`: the reach test now asserts the long grammar builds `NarrowLane`; the long
  differential test checks the narrowing against the evaluator at the default 300 iterations, and
  once at the nightly's 10,000.
- `VarkaEmittedBytesSuite`, `VarkaEmitCostAuditSuite`, `VarkaEmitCostSuite`,
  `VarkaEmitterDriverTableSuite`, `VarkaEmitterBudgetSuite`: every other consumer of the long
  draw, green against the regenerated files.

## 6. The measurement

The share of long shapes that carry a narrowing root, and the fuzzer's verdict on them.

### 6.1 Predictions, registered before the run

1. At least one long shape in twenty carries a narrowing root: a literal leaf, and a division by
   a micros-per-day or nanoseconds-per-second divisor, put a subtree under 2^31.
2. No mismatch at 300 iterations or at 10,000: the narrowing store has been run by the emitter
   suite's fixed shapes and every `TIME` kernel, and the new cases - a narrowing root beside plain
   roots, on masked and short batches - use the same store.
3. In `emitted_bytes.json`, only `fuzz_long` blocks move, and only for shapes in which a root's
   bound fits 32 bits; the int blocks and the curated shapes are byte-identical.

## 7. Risks

A drawn kernel that mixes a narrowing root with plain roots may hit an emitter path no fixed
shape has: that is the point of the task, and a mismatch becomes a finding with its own row.

## 8. Sequencing

One pull request: the plan, the grammar and suite change, the two regenerated files, and row 235
marked done.

## 9. Outcome

Done on 2 October 2026. `LongShapes.root` narrows a drawn value root where its bound fits an int,
one time in three; `drawLongShape`, `drawWideLongShape` and the long-lane reach test draw through
it; the long-lane differential test reads a narrowing root's output at four bytes a row; and the
long-lane reach test no longer excludes `NarrowLane`. As section 2 found, the int test keeps its
exclusion, now with the lane as the reason, so the row's "dropped from both" is one of two.

### 9.1 The predictions, scored

1. **Wrong, narrowly.** 455 of the first 10,000 long shapes carry a narrowing root, 4.6% against
   the one in twenty predicted; 379 of them beside a plain long root, the case no fixed shape had.
   The default run's 300 iterations hold 14, the same 14 on every CI run; the nightly's 10,000
   with the day's seed hold about 460.
2. **Held.** No mismatch at 300 iterations, nor at 10,000.
3. **Held for the int lane and the curated shapes, wrong about the rest of the long lane.** Every
   int-lane block and every curated shape is byte-identical. All 100 `fuzz_long` blocks moved at
   both widths, which a block of 100 shapes makes certain, and so did the `option_arms` digests,
   which the prediction missed: each is one digest over every shape at a width, the long corpus
   included.

### 9.2 What the plan did not foresee

**The price tables are fitted on the long corpus too**, so `VarkaEmitCostTable.java` and
`VarkaEmitCostRegister.java` are refitted (`VARKA_COST_REGEN=true` on `VarkaEmitCostSuite`), and
the audit regenerated after them; section 4 should have listed both files and the suite. The refit
moves no default emission: `emitted_bytes.json`, regenerated under the old prices, passes
unchanged under the new ones, since the default exact grouping admits groups by the weights and
the prediction that reads the prices is off by default.

**The audit, requoted.** No conclusion of `PLAN_TASK_199.md` 9 moves. At 2000 bytes and over the
fitted model's error is 2.2% at the median and 12.4% at the 99th percentile, as there, and the
register's 99th percentile is 69.1%. At 8000 bytes and over the fitted model now under-predicts
94.7% of 169 methods, against 90.1% of 161, by at most 4.9%: a planner that trusts the prediction
near the byte budget (task 236) needs about that margin.

**A second way to gain a loop method under `predictGrouping`.** `VarkaEmitCostSuite` pins the wide
shapes that gain one; the corpus move named new shapes, so the pinned list is redrawn. The int-lane
entry is the greedy close `PLAN_TASK_199.md` 9.3 records, the weights' class built twice. The two
long-lane entries, 59 and 78, are not: the weights build once, so no measurement splits a group,
and the prediction still closes one a method early - a prediction near the budget erring high,
where the greedy close follows one erring low. Both shapes gained so under the old prices as well,
so the refit did not cause it. The emitter's trace counts reactions to a measurement, not why a
prediction closed, so which budget fired is not known; task 236's planner should count both
directions.
