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

* a long value `a` from `longValue(depth - 1)`;
* the day: where `a.bound()` fits the guard's range, `NarrowLane(a)` itself; otherwise
  `NarrowLane(ConstDivide(...))` as `VarkaNodeCompiler.floorDivLong` lowers a floor division by
  `MICROS_PER_DAY` (the shift by its 13 trailing zeros, the odd part's biased `ConstDivide`
  under `EXACT_DIVIDEND_BOUND`), drawn only where the dividend's bound is under that bound, so
  that the long column's `LONG_COLUMN_BOUND` of `2^46` microseconds lands at about 814 days;
* `GuardedRange(day, NTZ_MIN_DAYS, NARROW_MAX_DAYS)`, in the `FAIL` mode `ntzDay` uses, and
  drawn only where the day's bound lies inside that range, so the guard never fires: a firing
  guard declines the batch and the reference evaluator has no spelling for that, the reason
  `Shapes`' own guard case gives;
* over it one of `Year`, `WeekOfYear(ThursdayOf(...))` (the pair as a unit, as `Shapes` case 16
  draws it) or `LastDay`, a third each, the three the row names.

`floorDivLong` is package-private in `codegen`; the grammar rebuilds its three nodes rather than
widening its visibility, with a comment naming it, and a test (5, item 3) holds the rebuilt day
to `floorDivLong`'s IR for the same input so the two cannot drift.

`boundsOf` already has arms for `NarrowLane` and `GuardedRange`. `fitsUnderChrono` is a method
of `Shapes`; `MixedShapes` gets the same test over the day (its bound and `boundsOf(...).guarded()`
within `CHRONO_BOUND`) before the calendar node is placed, as every calendar case of `Shapes`
asks it.

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
| `VarkaIrGrammar.java` | `NTZ_DAY_FUZZ_SEED`, the flag, the case, `drawMixedNtzDayShape` |
| `VarkaIrFuzzSuite.scala` | the side sequence's differential test and its reach test |
| `sql/varka/coverage/reach/VarkaIrFuzzSuite.md` | regenerated if it lists sequences |

## 5. Tests, and what each is for

1. `VarkaIrFuzzSuite`, "random NTZ-day trees of both lanes match the reference evaluator (seed
   `NTZ_DAY_FUZZ_SEED`, n iterations)": the mixed check (`runOneMixed`'s body, parameterised by
   the draw) over the side sequence, both widths as the suite runs them, its iterations under
   `-Dvarka.fuzz.ntzDayIterations` (default 300, as the narrow sequences have their own). It is
   the only test that holds the shape to the reference over random data and options.
2. "the NTZ-day draw reaches a year, a week and a last-day node over a guarded narrowing, and
   the committed mixed draw reaches none": over 2,000 side draws, each of the three calendar
   nodes is found with a `GuardedRange` child over a `NarrowLane`; over the reach test's 20,000
   committed draws, none. The second half is the test that the flag keeps the pinned corpus
   still, the 481 pattern `VARKA-352.md` 2.2 item 3 asks for.
3. "the NTZ-day draw's day is the compiler's": for a long column, the rebuilt divided day equals
   `ntzDay(NarrowLane(floorDivLong(c, MICROS_PER_DAY)))` as the compiler builds it for
   `year(CAST(ntz AS DATE))`, compared as IR. Catches a drift of either.
4. `VarkaEmittedBytesSuite` unchanged and passing: no pinned file moves.

## 6. The measurement

No speed claim. The row's measure is the fuzz at 10,000 iterations of the side sequence, run once
on the laptop with `-Dvarka.fuzz.ntzDayIterations=10000`, `-z` on test 1, and its pass count and
time recorded in section 9.

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

1. The guard's range test is wrong for a day the grammar's bound misjudges, and a batch
   declines: the mixed check asserts a zero status, so the test fails and names the draw.
2. The `floorDivLong` rebuild drifts from the compiler's: test 3.
3. A `rnd` call added on the flag-off path moves the committed blocks: test 2's second half and
   `VarkaEmittedBytesSuite`; `dev/varka_bytes_moved.py origin/master` prints nothing.

## 8. Sequencing

One pull request, well under the 1000-line cap (about 150 lines): the grammar and its seed, the
two suite tests, the reach report. The 10,000-iteration run is recorded in this file's section 9
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
