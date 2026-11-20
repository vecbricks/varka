# VARKA-579: A calendar node over a guarded narrowed long in the IR fuzzer's mixed draw

## 1. Where this came from

Milestone 8's row 579, found while building row 340's fields on 7 November 2026
(`VARKA-340.md` 10.1, "Found: a calendar node over a guarded narrowed long is not in the IR
fuzzer's mixed draw"). A date field of a `TIMESTAMP_NTZ` value compiles to a calendar node over
`GuardedRange(NarrowLane(floorDivLong(t, MICROS_PER_DAY)))`: `VarkaChronoCompiler.calendarDay`
wraps the compiled `CAST(ntz AS DATE)` in `VarkaNodeCompiler.ntzDay`, a guard from
`VarkaRangeAnalysis.NTZ_MIN_DAYS` to `VarkaChrono.NARROW_MAX_DAYS`, and row 339's month shifts
build the same day (`VarkaChronoCompiler.ntzMonthShift`). Only `VarkaTimestampNtzCastSuite`'s
curated kernels and the coverage rows reach that shape; the IR fuzzer, which holds every drawn
tree to the reference evaluator over random data, masks and options, never draws it.

## 2. The admission check, done

Read at origin/master `e5d70012bdc` (20 November 2026). The problem holds.

1. `VarkaIrGrammar.MixedShapes.intValue` draws seven cases: the LEGACY narrowing, the ANSI
   narrowing (`NarrowLane(GuardedRange(long, int range))`, the guard *under* the narrowing),
   the int grammar `Shapes` over the int columns only (`new Shapes(rnd, intInputs, intLiterals,
   -1, -1)`), and arithmetic, picks and a conditional. The calendar nodes are drawn by `Shapes`
   alone, whose leaves are int columns and int literals, so no calendar node in the mixed draw
   ever sits over a `NarrowLane`, guarded or not. No other grammar draws both lanes in one tree.
2. `VarkaIrFuzzSuite`'s reach test over the mixed corpus (20,000 draws at `MIXED_FUZZ_SEED`)
   asks for `calendar under a widening` and nothing over a narrowing; its `wanted` set is the
   whole of what the mixed draw promises.
3. The committed draw is pinned: `VarkaEmittedBytesSuite` hashes 10,000 shapes at
   `MIXED_FUZZ_SEED` as `fuzz_mixed` blocks in `emitted_bytes.json` at 4 and 16 lanes, and
   `VarkaWithChildrenSuite` and `VarkaIrFuzzSuite` read the same sequence. One `rnd` call more
   in `intValue` moves every shape after it. The emit cost corpus fits on `fuzz` and
   `fuzz_long` only (`VARKA-352.md` 0.1), so the cost model does not read this sequence.

What the check would have rejected: a mixed draw that already reached the shape through
another production, or a row 340 change that put the shape in the long grammar. Neither exists.

**The house rule since the row was written.** `VARKA-352.md` 2.2, planned on 30 October 2026
and so a week older than this row, freezes a committed sequence between refits and sends "a new
node on an existing lane" to a side sequence behind a flag the committed draws leave off,
joining the committed corpora at the next refit. Four precedents follow it in this grammar:
`UNSIGNED_FUZZ_SEED` (its javadoc: drawn into the long corpus it "would move its committed
blocks"), `NARROW_FUZZ_SEED`, `NULLING_FUZZ_SEED` and `NULLING_LANE_FUZZ_SEED`. The shape here
is new composition of existing nodes, not a new node, but the reason for the rule, that a pinned
corpus moves only at a refit, applies unchanged. The row's done-when asks for the pinned shapes
to be regenerated in a pull request of their own, which is the other way. Question 1 asks the
owner; this plan builds the side sequence and leaves the fold into `fuzz_mixed` to the refit.

## 3. The design

### 3.1 The production

`MixedShapes` gains a flag `ntzDays`, off in `drawMixedShape` and on in a new
`drawMixedNtzDayShape(Random)`, which draws exactly as `drawMixedShape` does with the flag on.
With the flag on, `intValue` takes one case more (`rnd.nextInt(8)`); with it off the call is
`rnd.nextInt(7)` as now, so the committed draws take the same values from the generator.

The new case builds the day the compiler builds and a calendar node over it:

* a long value `a` from `longValue(depth - 1)`, of bound `B = a.bound()`;
* the day: where `B` fits the guard's range, `NarrowLane(a)` itself; otherwise
  `NarrowLane(...)` over the five nodes `VarkaNodeCompiler.floorDivLong` builds for a floor
  division by `MICROS_PER_DAY`: the shift right by its 13 trailing zeros, the sign word (a shift
  by 63) ANDed with `1 - odd`, their `WRAP` add, and the odd part's `ConstDivide` with
  `floorDivLong`'s own fixed bound `(1L << (63 - 13)) + odd`, not the dividend's. That division
  is exact for every long, so it is drawn over any long value; the long column's
  `LONG_COLUMN_BOUND` of `2^46` microseconds lands at about 814 days;
* `GuardedRange(day, NTZ_MIN_DAYS, NARROW_MAX_DAYS)`, in the `FAIL` mode `ntzDay` uses, and
  drawn only where the day's interval (below) lies inside that range, so the guard never fires:
  a firing guard declines the batch and the reference evaluator has no spelling for that, the
  reason `Shapes`' own guard case gives;
* over it one of `Year`, `WeekOfYear(ThursdayOf(...))` (the pair as a unit, as `Shapes` case 16
  draws it) or `LastDay`, a third each, the three the row names.

**Pinned literal slots.** `floorDivLong` reads its constants 13, 63 and `1 - 10,546,875` through
`sink.longSlot`, and `runOneMixed` fills every long slot with `within(rnd, 2^20)`, which would
not be the compiler's division. So the case appends its constants as pinned slots past the drawn
long table (ordinals `longLiterals`, `longLiterals + 1`, ...), and `DrawnMixed` gains
`pinnedLongs`, the values of those last slots, empty for the committed draw (a second
constructor keeps `drawMixedShape`'s call). The side sequence's test fills the drawn slots as
`runOneMixed` does and then appends `pinnedLongs`, so with the flag off it draws the same
numbers from `rnd` as now.

**Both ends of the range.** Days from the long columns stay within about 814 days of the epoch, far
from the guard's ends, where the narrowed calendar lowering is most at risk. So one draw in four
takes its count from `a + k` (a `WRAP` add, exact here: `|k|` is under `2^60`) with `k` a pinned
slot: the shape draw picks an end, a day `kd` inside it by the span of `a`'s days
(`B / MICROS_PER_DAY + 1`) plus up to 1,000 more, and pins `k = kd * MICROS_PER_DAY`; it is drawn
only where that span leaves room inside the range. The day then lands near `NTZ_MIN_DAYS` or
`NARROW_MAX_DAYS` and still inside, so the guard does not fire.

**The day's interval, not `boundsOf`.** A magnitude bound cannot hold a day near `+11.4M` that
never goes below zero, and `boundsOf` would give the `k` slot `LONG_LITERAL_BOUND`. The case
therefore computes the day's interval itself, `[floorDiv(k - B, D), floorDiv(k + B, D)]` with
`k = 0` where none is pinned, and both the guard test above and the calendar node's admission
read that interval. The admission is not `fitsUnderChrono`: `CHRONO_BOUND`, 5,000,000 days, is
the int grammar's slack for an unguarded day and is below both ends (`NTZ_MIN_DAYS` about
-5,394,207, `NARROW_MAX_DAYS` 11,382,643), so it would refuse every end draw. The explicit guard
holds the day inside the narrowed range, which is what the compiler relies on in `ntzDay`. The
case keeps the other half of the check, `boundsOf(a.node()).guarded() <= CHRONO_BOUND`, for any
guarded day producer inside `a`. The node it returns has the calendar node's own bound, as in
`Shapes`, so a node above it reads `boundsOf` as before.

`floorDivLong` is package-private in `codegen`; the grammar rebuilds its five nodes rather than
widening its visibility, with a comment naming it, and a test (5, item 3) holds the rebuilt day
to `floorDivLong`'s IR for the same input so the two cannot drift.

### 3.2 What is deliberately unchanged

* `drawMixedShape` and `MIXED_FUZZ_SEED`: no committed draw moves, so `emitted_bytes.json`,
  `VarkaWithChildrenSuite` and the reach report do not move.
* The calendar lowering, the guard and the emitter: this row adds a draw, not a node.
* `TruncDate` over the day and the month shifts (row 339's `AddMonths` form): the row names a
  year, a week or a last day; the month shift's day is the same guarded narrowed long, so a
  later draw can add it in the same case.

### 3.3 Registered op counts

None: no kernel's bytes change.

## 4. Files

| file | what |
|---|---|
| `VarkaIrGrammar.java` | `NTZ_DAY_FUZZ_SEED`, the flag, the case, `pinnedLongs`, the draw |
| `VarkaIrFuzzSuite.scala` | the side sequence's differential test and its reach test |
| `sql/varka/coverage/reach/VarkaIrFuzzSuite.md` | regenerated if it lists sequences |

## 5. Tests, and what each is for

1. `VarkaIrFuzzSuite`, "random NTZ-day trees of both lanes match the reference evaluator (seed
   `NTZ_DAY_FUZZ_SEED`, n iterations)": the mixed check (`runOneMixed`'s body, parameterised by the
   draw, with `pinnedLongs` appended to the long literal table) over the side sequence, both widths
   as the suite runs them, its iterations under `-Dvarka.fuzz.ntzDayIterations` (default 300, as the
   narrow sequences have their own). It is the only test that holds the shape to the reference
   over random data and options.
2. "the NTZ-day draw reaches a year, a week and a last-day node over a guarded narrowing, and
   the committed mixed draw reaches none": over 2,000 side draws, each of the three calendar
   nodes is found with a `GuardedRange` child over a `NarrowLane`, and shapes whose day interval
   lies within 2,000 days of each end of the guard's range are drawn, read from the pinned `k`
   the shape carries; over the reach test's 20,000 committed draws, none. The
   second half is the test that the flag keeps the pinned corpus still, the 481 pattern
   `VARKA-352.md` 2.2 item 3 asks for.
3. "the NTZ-day draw's day is the compiler's": for a long column, the rebuilt divided day equals
   `ntzDay(NarrowLane(floorDivLong(c, MICROS_PER_DAY)))` as the compiler builds it for
   `year(CAST(ntz AS DATE))`, compared as IR with each `LiteralSlot` replaced by its value (the
   compiler's sink table on one side, `pinnedLongs` on the other), since the two number their
   slots differently. Catches a drift of either.
4. `VarkaEmittedBytesSuite` unchanged and passing: no pinned file moves.

## 6. The measurement

No speed claim. The row's measure is the fuzz at 10,000 iterations of the side sequence, run once
on the laptop with `-Dvarka.fuzz.ntzDayIterations=10000`, `-z` on test 1, and its pass count and
time recorded in section 11.

### 6.1 Predictions, registered before the run

1. The 10,000 iterations pass with no disagreement. A failure is a bug in the calendar lowering
   over a narrowed long, which is what the row exists to find, and becomes a row of its own.
2. Time: the mixed corpus's 300 default iterations are a small share of the suite's 298 s
   (`suite-times.tsv`), whose compiled test's warm-up waits are 81 to 88% of it (calibration
   row VARKA-394). At an estimated 20 to 50 ms an iteration, 10,000 take 3 to 8 minutes in one
   JVM; no calibration row prices an IR fuzz iteration, so the band is stated wide.
3. The default 300 iterations add under 15 s to `VarkaIrFuzzSuite`, the same estimate at the
   VARKA-243 ratio of 2 for a new test's setup.

## 7. Risks

1. The day interval of 3.1 is wrong for some draw, and a batch declines on the guard: the mixed
   check asserts a zero status, so the test fails and names the draw.
2. The `floorDivLong` rebuild drifts from the compiler's: test 3.
3. A `rnd` call added on the flag-off path moves the committed blocks: test 2's second half and
   `VarkaEmittedBytesSuite`; `dev/varka_bytes_moved.py origin/master` prints nothing.

## 8. Sequencing

One pull request, well under the 1000-line cap (about 180 lines): the grammar and its seed, the
two suite tests, the reach report. The 10,000-iteration run is recorded in this file's section 11
in the same pull request.

## 9. Questions for the owner

1. **Side sequence, or move `fuzz_mixed` now?** The row says the pinned shapes are regenerated
   in a pull request of their own; `VARKA-352.md` 2.2 says a committed sequence moves only at a
   refit, and every fuzzer addition since has taken a side sequence. *Recommendation:* the side
   sequence, folded into `MIXED_FUZZ_SEED` at the next refit, because it tests the same shape
   with no pinned file moving and follows the rule the four later precedents follow. I cannot
   decide it because the row's done-when and the plan rule disagree and both are the owner's.
   If the owner prefers the row's form, the flag is dropped, the case joins `intValue`'s committed
   draw, and a second pull request regenerates `emitted_bytes.json`'s `fuzz_mixed` blocks with
   `dev/varka_bytes_moved.py`'s output quoted.

## 10. Done when

Restated on the recommended answer: the mixed grammar draws a year, a week or a last-day node
over a guarded narrowed long in a side sequence of its own, a test shows the committed mixed
draw reaches none of it and no pinned file moves, and the side sequence passes the reference
evaluator at 10,000 iterations, the run recorded here; the fold into `fuzz_mixed` waits for the
next refit.

## 11. Outcome

*Filled in when the work lands: the runs with their commands, section 6.1's predictions scored
one by one, and what moved that this plan did not list.*
