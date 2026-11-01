# VARKA-431: The IR fuzzer draws `<=>` and the mixed-lane bitwise shapes (rows 431 and 432)

*Rows 431 and 432 of `m8/PLAN.md` share this file. Both are grammar gaps in `VarkaIrGrammar`,
both are "taken with 352's refit" in their cells, and both follow the same draw discipline
(`VARKA-352.md` 2.2). Row 431 adds `NullSafeEqual` at both lanes. Row 432 adds the long `BitOp`
over a widened int count and the narrowed popcount in the mixed grammar. They are built
together because one side sequence and one flag carry both.*

## 0. What exists now (admission check)

Read on 1 November 2026 from the worktree at `130280562f3` (code as of master `5c660201b35`); the
line numbers are that tree's. Master has since moved to `1bb9c97b5d0`, whose diff to the files these
rows touch (`Analysis.java`, `Slots.java`, `VarkaIrGrammar.java`, row 157's decimal store) shifts
some lines and changes no finding. Nothing was run.

1. **Neither shape is drawn.** `VarkaIrGrammar` builds no `NullSafeEqual`. Its only mention is
   `boundsOf` (`:273`). `VarkaIrFuzzSuite`'s reach tests subtract `"NullSafeEqual"` from the
   nodes they demand, at the int lane (`:999`) and the long lane (`:1027`), with the reason in
   words (`:981-986`). `VarkaWithChildrenSuite` does the same (`:60`, `:71`) and rebuilds the
   node by hand (`:100-102`). `VarkaEmitCostSuite`'s `awaitingRefit` set holds it (`:85`).
2. **The mixed grammar's long values.** `MixedShapes.longValue` (`VarkaIrGrammar.java`, the
   `MixedShapes` class) draws the widening, `LongShapes`' grammar over the long columns, add and
   subtract, `greatest`/`least` and `IfElse`. `BitOp` and `BitCount` appear only inside the
   pure-int `Shapes` and the pure-long `LongShapes`. So no draw makes `BitOp(op, long,
   WidenLane(int))` or `NarrowLane(BitCount(long))` as an int operand. Both shapes compile from
   SQL since 335b's first two items (`VARKA-335.md` 9.4: `shiftleft(l, i)` fuses as
   `(bit:SHL col:0:long (widen col:1))`, and `bit_count(l) + 1` as
   `(int:ADD:WRAP (narrow (bitCount col:0:long)) lit:0)`).
3. **Row 352 plans the fold.** `VARKA-352.md` 2.3 puts both rows in **refit A**, 352a's last
   step, with the boolean nodes and the narrow leaves. Its done-when says "rows 431 and 432 are
   closed by it". Its question 1 (the refit schedule) has no recorded answer yet. 2.2 rule 3 is
   the discipline for a new node on an existing lane: "a side sequence of its row's seed, behind
   a flag the committed draws leave off, with a test that the committed draws reach none of it".
   Row 481's nulling draw is the worked pattern (`NULLING_FUZZ_SEED`, `drawNullingShape`, the
   `nullingGuards` flag tested before any `rnd` call, `VarkaIrFuzzSuite`'s "the nulling draw
   reaches a nulling range guard at both lanes, and the committed draws ..." test, `:1348`).
   Row 157 added a fourth such sequence, `DECIMAL_FUZZ_SEED` (#1249, `083362ffcd0`).
4. **Since the row was written, six more nodes are undrawn** for the same reason: the five
   boolean nodes and `CondValue` (rows 331, 460 and 27), plus `DecimalColumn` and `MathUnary`
   (`VarkaIrFuzzSuite.scala:999-1001`). They stay refit A's (352).
5. **What "computed operands with a validity word of their own" means here.** `Analysis` gives
   every value node a `WordOwner`. A column's is `Input`, a literal subtree's is `Const`, and a
   node that computes its word into a slot of its own, such as `IfElse`'s blend, is `Own`
   (`Analysis.java:111-114`, `:1368-1409`). Row 336's review added three coverage rows for such
   operands (`coalesce(d, d2) <=> d`, `greatest(d, d2) <=> d2`, `if(i > 3, d, d2) <=> d`).

### What differs from the row text

- The rows' done-whens include the refit: `enumeratedFeatures` carries the node's features, and
  the bytes and the suites are regenerated. That is refit A's work in 352a. This plan builds the
  productions first, in a side sequence, so that the fuzzer runs the shapes against the
  reference now. The fold into the committed draws stays in refit A. Each row is **Done** when
  refit A merges, as 352's done-when says (question 1).
- Row 432's "a long `BitOp` whose count is a widened int expression" covers the three shifts.
  A widened int right operand of `&`, `|` and `^` is the same production with no extra cost, and
  `VarkaEmitterBitwiseSuite` already runs "an XOR with a widened int expression". The plan draws
  all six `BitOpKind`s.

## 1. Goal and non-goal

Goal: the IR fuzzer draws `NullSafeEqual` over value operands at the int and long lanes and over
both lanes, `BitOp` over a long value and a widened int expression, and a narrowed popcount as an
int operand in the mixed grammar, each run against the reference evaluator, in a sequence of its
own that no committed output reads.

Non-goal: pricing the node, moving any committed draw, `emitted_bytes.json`, the cost corpus or
`VarkaWarmupReach`. All of that is refit A (352a).

## 2. The design

### 2.1 One flag, one side sequence

`VarkaIrGrammar` gains `GAP_FUZZ_SEED` (a new date-shaped seed, like the others) and a boolean
`gapNodes` on `Shapes`, `LongShapes` and `MixedShapes`, threaded the way `nullingGuards` is.
`drawGapShape`, `drawGapLongShape` and `drawGapMixedShape` draw with it on. Every committed draw
passes `false`. Rule: the flag is tested before any `rnd` call it guards, so with it off the
sequence of `rnd` calls is byte for byte what it was.

### 2.2 Row 431: the null-safe equality

- `Shapes.cond`, `LongShapes.cond`, `MixedShapes.intCond` and `MixedShapes.longCond`: in the
  comparison leaf, with `gapNodes` on, one time in three the two drawn operands make
  `NullSafeEqual(left, right)` instead of `Compare(op, left, right)`. That is the nulling draw's
  one-in-three on an existing arm. The operands are the same `value(depth)` draws, so they
  include `IfElse`, `Greatest`, `Least` and every computed value the grammar already makes. The
  `op` draw still happens so that the operands' draws match the `Compare` form.
- In the mixed grammar, `MixedShapes.longCond`'s leaf can also take
  `NullSafeEqual(WidenLane(intValue), longValue)`, the `l <=> i` form the compiler builds
  (`VARKA-336.md` 2.3, `onLongLane`), one time in three of the times it takes `NullSafeEqual`.
- `boundsOf` already answers `NullSafeEqual` (`:273`), so nothing changes there.

### 2.3 Row 432: the mixed-lane bitwise shapes

- `MixedShapes.longValue`, case 3 (add and subtract): with `gapNodes` on, one time in three it
  becomes `BitOp(op, longValue(depth - 1), WidenLane(intValue(depth - 1)))` over the six kinds.
  Its bound is `Long.MAX_VALUE`, as `LongShapes`' `BitOp` arm carries (`:1173-1175`), so
  `mode()` above it draws only `WRAP`.
- `MixedShapes.intValue`, case 3: with `gapNodes` on, one time in three its right operand is
  `NarrowLane(BitCount(longValue(depth - 1)))`, bound 64. That is the narrowed popcount as an
  int operand, the bound `VarkaRangeAnalysis` answers for it (`VARKA-335.md` 9.4).

## 3. Tests

1. **The side sequence against the reference**, three tests in `VarkaIrFuzzSuite`, one per
   lane, beside the nulling ones (`:1332`), with the seed and count in the title (352 2.2 rule
   6): "random IR trees with the gap nodes match the reference evaluator, at the int lane
   (seed ..., N iterations)", and likewise at the long lane and both lanes. They run the same
   check as the committed draws.
2. **Reach and isolation**, one test like `:1348`. Over 20,000 draws of each gap sequence, it
   asserts that:
   - `NullSafeEqual` is drawn at each lane;
   - at least one drawn `NullSafeEqual` has an operand whose `WordOwner` is `Own`;
   - `BitOp` over a `WidenLane` right child is drawn, with each of the three shifts;
   - `NarrowLane(BitCount)` is drawn under an int `IntArith`;
   - the committed sequences (`FUZZ_SEED`, `LONG_FUZZ_SEED`, `MIXED_FUZZ_SEED`) reach none of
     these at the same counts.
3. **The words in the reach tests.** `VarkaIrFuzzSuite` `:981-986` and `VarkaWithChildrenSuite`
   `:60` say the node is "in no grammar". They change to "in the gap sequence, not the committed
   ones, until refit A (VARKA-352)". The subtractions stay until refit A removes them.

No pinned fixture moves: `emitted_bytes.json`, `emit_cost_audit.json`, the reach report and
`known_failures.tsv` read only the committed sequences. `dev/varka_bytes_moved.py`, which 352
2.2 plans, does not exist yet, so the check is `VarkaEmittedBytesSuite` passing unchanged.

## 4. Predictions and costs, registered before the build

- P1: no committed digest moves (`VarkaEmittedBytesSuite` green with no regeneration).
- P2: the three new fuzz tests add under 60 s to `VarkaIrFuzzSuite` in one JVM, at the nulling
  tests' iteration count. The suite's time is mostly its compiled test's warm-up waits
  (VARKA-394 in the calibration table: 248 s for the suite, 81 to 88% of it waits), which these
  emission-and-reference tests do not have.
- P3: the side sequence finds no disagreement. If it finds one, the disagreement is a row of its
  own, or a fix in this PR if it is in the grammar.

## 5. Order, files and size

| File | Change |
|---|---|
| `VarkaIrGrammar.java` | `GAP_FUZZ_SEED`, the flag, the three draws, 2.2 and 2.3's arms |
| `VarkaIrFuzzSuite.scala` | tests 1 and 2, the comment of test 3 |
| `VarkaWithChildrenSuite.scala` | the comment of test 3 |
| `sql/varka/plans/m8/VARKA-431.md` | the outcome |

One pull request, verified by its PR Build and an option-matrix dispatch. The new tests are
seeded, so they join row 499(c)'s seeded set if that has landed. Then refit A (352a) folds the
flag's arms into the committed draws, removes the subtractions and the `awaitingRefit` entry,
and adds `NullSafeEqual/<lane>` to `enumeratedFeatures`. Size: small for this pull request
(about a day). The refit's share is counted in 352's size.

## 6. Out of scope

- The fold, the price and every regenerated output: refit A (352a).
- `BoolNullSafeEq` and the other boolean nodes: refit A, as 352 lists them.
- Narrow and floating operands of `<=>`: their lanes' sequences (352 2.2 rule 2).

## 7. Questions for the owner

1. **A pull request before the refit, or all in 352a.** (a) this plan's side sequence now, and the
   fold in refit A; (b) nothing now, everything in refit A. *Recommended: (a).* The shapes then
   meet the reference on every build from now on rather than from refit A's merge, and the fold
   becomes a flag removal. The cost is one more seed to keep, which 352 2.2 rule 3 already
   expects.
2. **When the rows close.** Each row stays open until refit A merges, with a "Planned" cell that
   names this plan and refit A. *Recommended: yes*, since the done-whens name the refit.

## 8. Done when

- the gap sequence draws `NullSafeEqual` over value operands at both lanes and over both lanes
  (with an operand that owns its word among them), the mixed long `BitOp` over a widened int
  expression and the narrowed popcount, each checked against the reference on every build;
- no committed digest moved in that pull request;
- refit A (352a) has folded them, with `enumeratedFeatures` carrying `NullSafeEqual`'s features
  and the regenerated outputs listed in its commit, which is the rows' original done-when.

## 9. Open points for the lead

1. `VARKA-352.md` question 1 (the refit schedule) is the gate for both rows' closing. If the
   owner chooses one refit at the close (352's option (b)), both rows wait for it.
2. 352's section 7 and its done-when already name these rows. If question 1 is
   answered (a), 352's step list should say refit A removes the gap flag.

## 10. Outcome

(Filled in when the rows are built.)
