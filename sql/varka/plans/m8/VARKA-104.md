# VARKA-104: `Long` arithmetic, VARKA-30's int64 half

*Written 18 September 2026. Section 2.39 of `m5/PLAN.md` opened this
task on 15 September 2026 by the milestone's re-scope, and adjusted it on 17
September: comparisons over a `bigint` column landed with VARKA-29, and this task
keeps every arithmetic form - `bi + 1` included - because the overflow-mode
decision is its subject.*

*Brought up to date on 16 October 2026, against `m8/PLAN.md` (row 104, wave 0, beside 28) and its
review of the same day (section 10), at `8a50f61a368`. The September text stands; each place where
the code or the record has moved carries a dated correction. In short: the arms and the overflow
decision now live in Java (`VarkaNodeCompiler.java`), and `arithOver` is shared with the interval
compiler; the lattice's long refusal is unchanged; division is no longer this task's but row 30's;
option B's AVX2 caveat is now a source-read fact (10.1 item 4); and option C is the limb
construction row 361 also needs.*

## 0. Admission check, 9 November 2026

Read against `origin/master` at `b90e6799220`. What has merged since 16 October and what it changes
here; file and line are at that commit, under `sql/catalyst/.../expressions/codegen/`.

1. **A baseline comes first.** Section 8 starts with the compiler, but the repository's rule since
   this plan was written is that a speedup first merges a row-path baseline of the unchanged code
   as its own pull request (as rows 103, 332, 342 and 343 did). No committed file times `BIGINT`
   arithmetic on the row path: `VarkaTwoLaneLongBenchmark` has `l + l2` only as a laptop-run
   control. So step 1b below is `VarkaBigintArithmeticBaselineBenchmark`.
2. **`bi + 1` keeps its check, and 3.2, test 2 of section 5 and 6.1's prediction 2 ("`bi + 1`
   reaches unchecked throughput") are wrong to say otherwise.** A `bigint` column is unbounded, and
   `Long.MAX_VALUE + 1` overflows, so no lattice can take the check off a sum with a column leaf.
   What the long half proves is a tree whose every long leaf is bounded: a long literal, a widened
   int (`CAST(i AS BIGINT) * 1000`, below `2^41`), a long-lane `GuardedRange`. Test 2 becomes
   `CAST(i AS BIGINT) + 1` with no check and `l + 1` with one; prediction 2 is scored on
   `CAST(i AS BIGINT) + 1` and `CAST(i AS BIGINT) * 1000` against their LEGACY twins, and `l + 1`
   keeps the check like `l + l2`. The refusal is still one early return, `varka/VarkaRangeAnalysis.java:247`;
   lifting it for the long lane makes the `LiteralSlot` arm read a long slot index through the int
   table's accessor, risk 2 as a concrete wrong bound, so the long table needs its own accessor
   (`DeclineSink.longLiterals`), dispatched on the slot's lane.
3. **The saturation rule is lane-dependent in one place.** `boundedByIntMax`
   (`VarkaNodeCompiler.java:1121`) serves the int arms, the int negation (605) and the interval
   arms (`VarkaIntervalCompiler.java:277`, 326). At the long lane the question is whether the range
   is bounded at all, which the `*Exact` saturation of `VarkaValueRange` already answers; at the
   int lane it stays `<= Integer.MAX_VALUE`, so the int and interval bytes do not move. The narrow
   lanes stay unranged (`varka/VarkaRangeAnalysis.java:237`, row 329's rule in `arithOver`).
4. **Checked long `+` and `-` already run in production**: the `TIMESTAMP_NTZ` arms of row 339
   build `IntArith` with `FAIL` on the long lane (`VarkaTimeCompiler.java:395`, 423, 426). Section
   2.1's emitter claim is now tested by shipped shapes, not only by `VarkaLaneTypeSuite`.
5. **ANSI `abs` needs one emitter change.** `emitIntNeg` refuses a long `abs` in any mode but
   `WRAP` (`varka/VarkaVectorWalk.java:1283`), since only a decimal reached it; the negation's
   `Long.MIN_VALUE` compare above it is already the check. LEGACY `abs` takes the existing blend.
   `INT` `abs` is not fused either (census `abs` line), and is not this row's.
6. **Option B on AVX2 (#1424).** `L2D` now has exact AVX2 forms (`wideConversionForm`, `SPLIT` by
   default, `varka/VarkaEmitOption.java:596`), but only where the session names an emit level;
   the default, unknown level still declines the conversion (`VarkaEmitOptions.levelUnknown`,
   681). So B is buildable on the runners at the split's cost and is measured there rather than
   ruled out, but cannot be the default; A ships first and C stays the exact candidate.
7. **The two-lane hosts (row 503).** A long kernel that checks overflow builds a mask, which C2 on
   x86 does not lower at two 64-bit lanes (`VarkaTwoLaneLongBenchmark`'s javadoc); 503 refuses
   only filter conditions there. So the checked arms are measured at 128 bits before they are
   admitted at two lanes, and the baseline's 128-bit companion is the file that says.
8. **The mixed-lane rule (rows 28 and 343)**: `i + l` arrives as `CAST(i AS BIGINT) + l`, a
   `WidenLane` (`VarkaNodeCompiler.java:577`), so both operands are on the long lane, and
   `arithOver` declines a pair of lanes (1245) as it should. Row 344's `floor` and `ceil` over
   `BIGINT` (#1404) and row 481's `NULL` guard change nothing here: `TRY` is `Overflow.NULL` on
   the lane-generic check.
9. **The census** still names 104 on `div`, `%`, `mod`, `pmod` and `try_mod`; row 30's plan
   branch does not touch it, so the compiler step's pull request moves those to 30, as 3.4 says.

## 1. Where this came from, and what it owes

VARKA-30 was narrowed on 4 September 2026 to the int32 forms and shipped them in
VARKA-63. What it deferred was the int64 half, and the long lane makes it due.
VARKA-30's row closes when this one does.

**Dependencies, checked rather than assumed.** Section 2.39's scope sentence
ends "and `Cast` between int and long as 2.2's conversion", and the milestone's
wave table lists 104 after 29, 84 and 28's cast. Of those:

* **29** (the long lane itself) - merged.
* **84** (the value-range lattice) - merged, and section 3.2 below is about the
  one thing it does not yet do.
* **28** (lane-width conversion) - planned (`VARKA-28.md`), not built. It
  owns the int-to-long cast, which this task *consumes* rather than builds.

*Still so on 16 October 2026*: 28 and this row are both in milestone 8's wave 0, run side by side,
and only step 5 of section 8 waits for 28 to merge. The two share no file except
`VarkaNodeCompiler.java`, where 28 adds the cast arms and this row the long arithmetic arms; they
are adjacent switch arms, so whichever merges second rebases over the other.

So the arithmetic that is this task's subject - checked and wrapping `+`, `-`,
`*` and negate over `bigint` columns and literals - is single-lane work that
needs no conversion and is unblocked today. Only the cast clause waits on 28,
and it is 28's deliverable in the first place. Section 3.3 is the one place the
two genuinely meet, and it says so there.

## 2. What already exists, which is more than the row suggests

Reading the emitter before planning changed what this task is.

### 2.1 The overflow test is already lane-generic

`emitIntArith` and `emitIntNeg` were converted to `Lane` descriptors by VARKA-85
step 3 and carry no int32 assumption: the lanewise op, the two XORs, the AND, the
sign compare and the broadcast all go through `analysis.lane.lanewiseVV`,
`analysis.lane.compareVI`, `analysis.lane.pushScalar` and
`analysis.lane.vector`. `VarkaLaneTypeSuite` already asserts `IntArith` and
`IntNeg` are emittable at `LONG`, and `VarkaReferenceEvaluator.evalLong` already
has arms for both.

*Checked again, 16 October 2026:* `emitIntArith` and `emitIntNeg` are now in
`varka/VarkaVectorWalk.java` (412 and 479), still through the lane's descriptors.

**So checked add, subtract and negate at the long lane are already emitted
correctly, and nothing in this task has to build them.** What stops `bi + 1`
fusing is the *compiler*, which VARKA-29 deliberately narrowed to comparisons,
`greatest`/`least` and `CASE WHEN`.

### 2.2 What is actually missing

1. **The compiler arms.** `Add`, `Subtract`, `Multiply` and `UnaryMinus` over
   `LongType`, with the overflow mode read off `evalMode` the way the int32 arms
   read it. Mostly a matter of widening the existing type gate.
2. **The value-range lattice cannot see a long value at all**, which is section
   3.2 and the load-bearing gap.
3. **The checked multiply has no lanewise test at either width**, which is
   section 3.3 and this task's one real design question.

## 3. The design

### 3.1 The compiler arms

*Correction, 16 October 2026: the compiler is Java.* The arms are in `VarkaNodeCompiler.java`:
`Add`, `Subtract`, `Multiply` and `UnaryMinus` gated `when isInt(...)` (218 to 224), the shared
body `intArith` (450 to 469), and the overflow decision `arithOver` (479 to 496), whose
`cannotOverflow` and `boundedByIntMax` (424 to 441) compare a magnitude against
`Integer.MAX_VALUE`. **`arithOver` is shared with `VarkaIntervalCompiler`** (225, 296, 301, 334,
365: the year-month interval arms), so making the saturation rule lane-dependent touches the
interval arms' path too; those are int-lane and must keep their bytes, which the oracle checks.
The names below (`intOperand`, `DeclineSink.longSlot`) still exist; the long gate is
`VarkaNodeCompiler.laneOf` (279).

`intOperand` gates on `IntegerType`; the long arms need the sibling gate that
`laneOf` already provides, and `DeclineSink.longSlot` already registers a 64-bit
literal (VARKA-29 built it). `arithOver`'s structure carries over unchanged - a
declared mode, a bound that may demote it to `WRAP`, and a refusal for the
multiply it cannot prove.

Spark's semantics are the int32 arms' own, at the other width: ANSI throws,
non-ANSI wraps, `try_*` nulls. A lane cannot throw, so `FAIL` declines the batch
through VARKA-26's guard channel and the row engine raises the identical error on
the identical row.

### 3.2 The lattice has no long half, and that is what removes the checks

`VarkaRangeAnalysis.range` opens with an explicit refusal:

> *A leaf on a wider lane has neither of the two things this analysis reads: the
> epoch-day contract is an int32 statement, and `literals` hands back an int,
> which cannot carry a 64-bit constant. Answering UNKNOWN is the safe direction
> ... and it is what a wider lane gets until the analysis has a contract and a
> literal table of its own.*

That refusal is correct and it is also this task's blocker, because
`cannotOverflow` is the only thing that takes a check off, and at the long lane
it can never be true today. Every checked long op would keep its four-op test
even where a literal operand makes overflow impossible, and every checked long
multiply would decline unconditionally.

Two concrete things are owed, both named in the refusal itself:

* **A long literal table the analysis can read.** `magnitude` takes an
  `IntUnaryOperator`; the long lane needs the `LongUnaryOperator` sibling, fed
  from the compiler's long literal map.
* **A range type that holds 64-bit bounds.** `VarkaValueRange.Bounded` is
  already `long lo, long hi`, so the interval arithmetic needs no widening -
  what needs care is the *saturation* rule. At the int lane a bound is compared
  against `Int.MaxValue`; at the long lane the same comparison must be against
  `Long.MaxValue`, and `cannotOverflow`'s `_ <= Int.MaxValue.toLong` is an int32
  statement that must become lane-dependent rather than be widened silently.

There is no epoch-day contract at the long lane and none is invented: a
`bigint` column is unbounded, a `TIME` column is bounded by its type
(`m5/PLAN.md` 2.37: below 2^47), and a day-time interval is not. So the
lattice's long half starts as "literals and the types that bound themselves",
which is exactly enough to take the check off `bi + 1` and not enough for
`bi + bi2`. That asymmetry is the honest outcome and the tests should pin it.

### 3.3 The checked multiply: the option space

At int32 the intended mechanism was recorded in `VARKA-63.md` 3.4 and quoted
in the compiler today: *"the overflow test for `*` needs the 64-bit product or a
lane division, and the emitter has neither in int lanes, so an unprovable `ANSI`
or `TRY` multiply stays on the row engine until milestone 5's long lanes
arrive."* That fix is **VARKA-28 plus this lane**: widen both int32 operands,
multiply in int64 where `2^31 * 2^31 = 2^62` cannot overflow, and range-check the
result. It closes VARKA-30's last int32 gap and it is the one place this task and
28 genuinely meet.

At **int64 there is no wider lane**, and `VectorOperators` has no multiply-high
on any lane type. Three candidates, to be built and measured rather than argued
between:

| | mechanism | cost | domain |
|---|---|---|---|
| **A** | bound-only: 3.2's lattice proves it, or decline | 0 ops when proved | literals and self-bounded types only |
| **B** | double-lane magnitude pre-check: `\|(double)a * (double)b\| < 2^62`, lanes failing it decline the batch | two `L2D`, one multiply, one compare | every operand, but conservative - declines some products that would not overflow |
| **C** | 64x64 high-product emulation from 32-bit halves | four multiplies plus shifts and adds | exact, every operand |

**B is sound but conservative**, and worth stating why: converting a long above
2^53 to double loses precision, but the loss is *relative*, so the product
estimate is within about 2^-52 of the true magnitude. Testing against 2^62 when
the real limit is 2^63 leaves a factor of two of margin, which is enormous beside
that error. It can therefore never admit an overflow; it can only decline a
product that would have been fine, and declining is always safe.

*Added 16 October 2026.* The review read the cause in the source (`m8/PLAN.md` 10.1 item 4):
`x86.ad`'s `match_rule_supported_vector` refuses a vector cast from long to a floating type and
back without AVX512DQ (lines 1757 to 1777), so on the EPYC 7763 runners, which are AVX2, option B's
two `L2D` are not intrinsic at all, whatever the species. Row 376's census records it per host.
Option B is therefore an AVX512DQ-only form, chosen per host statically, as row 30 chooses its
division form; on AVX2 the choice is between A and C.

Option C is the 64 by 64 to 128-bit product from 32-bit limbs that row 361 builds for decimals
(`m8/PLAN.md` 2.8): only its high half is needed here, compared with the low half's sign. Two
facts of that row's reading apply to C: JDK 25 emits one `vpmuludq` for a 64-bit lane multiply
only when both inputs are visibly masked to 32 bits or shifted right by 32 or more, so a broadcast
constant limb is written `broadcast(c).and(0xFFFFFFFF)`, checked with `dev/varka_emit.sh --asm`;
and the carry-free limb order with the `unsignedMultiplyHigh` sign correction needs no compares.
If C is built here first, it is built as a helper 361 reuses, not a second copy.

**B has an AVX-level caveat that A and C do not.** `dev/varka_canary/L2DProbe.java`
records that under `-XX:UseAVX=2` the long-to-double converts fail to inline and
the loop degrades to scalar. So B is a good default at AVX-512 and possibly a bad
one at 128-bit, which makes it exactly the kind of choice this project measures
at both widths rather than picking once.

**A is the floor** and ships first regardless: it is the existing mechanism, and
3.2 is what makes it reach anything at all.

### 3.4 Division, modulo and `pmod`

*Superseded, 16 October 2026.* Milestone 8 gives the integral division family to row 30: `div`, `%`
and `pmod` over `INT` and `BIGINT`, a constant divisor by `emitConstDivide`, and a `BIGINT` column
divisor by Monniaux and Pain's 64-bit algorithm over the whole range (`READING_M8_PAPERS.md` 1),
so the "declined until VARKA-64" below no longer holds and VARKA-64 itself is out of milestone 8
(`m8/PLAN.md` 8). `/` over integers arrives as a division of two casts to `DOUBLE`, which is row
342's. This row builds no division; the census's `rows` column also names 104 on `div`, `%`,
`mod`, `pmod` and `try_mod`, and this row's pull request corrects those lines to 30.

`div`, `%` and `pmod` are divisions and take 2.19's rule - exact under a proven
bound, declined otherwise. For a `bigint` column with no statistics that means
**declined**, until VARKA-64's statistics-directed bounds return from milestone 6.
VARKA-88's double-lane division serves a *constant* divisor; `bi % bi2` has none,
and there is no lanewise integer divide, so the shape declines on the divisor
rather than on the bound.

`/` is a double and stays out of the milestone with item 3.

### 3.5 What is deliberately unchanged

* **The int32 arms.** Their bytes must not move; `VarkaEmittedBytesSuite` is the
  check, and section 2.1 is why nothing about them needs to.
* **VARKA-29's comparisons.** They are shipped and this task does not revisit
  them.
* **The guard channel.** `FAIL` declines the batch through VARKA-26's mechanism,
  not a second one.

## 4. Files

*Superseded on 16 October 2026 by the list below the first table: the Java compiler, the
emitter split, and the interval arms that share `arithOver`.*

| file | what |
|---|---|
| `VarkaRangeAnalysis.java`, `VarkaValueRange.java` | the long half of the lattice, and a saturation rule that is lane-dependent rather than int32 |
| `VarkaExpressionCompiler.scala` | the four long arithmetic arms, the long literal table reaching the lattice |
| `VarkaLoopEmitter.java` | only the multiply, and only if 3.3 picks B or C; A needs no emitter change at all |
| `VarkaReferenceEvaluator.scala` | the checked-multiply arm at `long`, which throws today |
| `VarkaIrGrammar.scala` | long arithmetic productions for the fuzzer |
| `VarkaCoverageSuite.scala` | the `bigint` arithmetic rows |

* `.../codegen/varka/VarkaRangeAnalysis.java`, `VarkaValueRange.java`: the lattice's long half, a
  lane-dependent saturation rule
* `.../codegen/VarkaNodeCompiler.java`: the long arms; `arithOver`'s bound read per lane
* `.../codegen/VarkaIntervalCompiler.java`: none expected; listed because it calls `arithOver`
* `.../codegen/varka/VarkaVectorWalk.java`: only the multiply, and only if 3.3 picks B or C
* test: `VarkaReference.java`: the checked multiply at `long`
* test: `VarkaIrGrammar.java`: long arithmetic productions
* test: `VarkaCoverageSuite.scala`; `coverage.json`, `docs/sql-varka.md`: the `bigint` rows,
  regenerated
* `sql/varka/proofs/` (the range-analysis files), `VarkaProofFiles`: the long half's rules proven as
  the int half's are (VARKA-281)
* `sql/varka/plans/m8/census_curated.txt`, `census.tsv`: `BIGINT` in `+`, `-`, `*`, `negative`,
  `abs`, the `try_` forms; 104 off the division lines

## 5. Tests

1. **The differential at the long lane** over `Long.MinValue`, `Long.MaxValue`
   and their neighbours, for each of `+`, `-`, `*` and negate, in all three
   modes. The extremes are the whole of the overflow test's domain.
2. **The check comes off exactly where the lattice proves it** - `bi + 1` with
   no test emitted, `bi + bi2` with one - asserted as an op count, because that
   is the difference this task's lattice work exists to make.
3. **`FAIL` declines the batch** rather than wrapping, as a status bit.
4. **The int32 arms' bytes do not move**, by the oracle.
5. **The multiply's chosen mechanism at both widths and both AVX levels** if
   3.3 picks B, because that is where its answer changes.

*Added 16 October 2026.*

6. **The interval arms' bytes do not move** when `arithOver`'s bound becomes lane-dependent; the
   bytes oracle over the year-month rows holds it.
7. **The long half's rules are proven** in the range-analysis proof files the way VARKA-281 proved
   the int half's (a bound past `Long.MAX_VALUE` is the case that matters, risk 1), so the
   "wrong-answer bug" of risk 1 has a proof against it, not only a test.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. **Checked long add costs the same four ops as checked int add**, since the
   emission is the same code at a different descriptor - so its throughput ratio
   against unchecked is the int32 ratio, and the lane's own 1.5x-2.0x cost
   (VARKA-142) is the only other difference.
2. **The lattice's long half removes the check from literal operands and nothing
   else**, so `bi + 1` reaches unchecked throughput and `bi + bi2` does not.
   This is a prediction about coverage, not speed, and 5.2 is where it is read.
3. **Option B loses to A at 128-bit** and may win at AVX-512, for the converts'
   inlining rather than for the arithmetic.

*Added 16 October 2026.* Prediction 3 is now partly settled by the source: on an AVX2 host B's
converts are not intrinsic (10.1 item 4), so B is measured only on AVX512DQ hosts and C against A
on AVX2. The 128-bit numbers carry the release rate beside them, since `m7` row 374 closed
without a fix (`m8/PLAN.md` 10.3 item 4).

*Corrected 10 November 2026 (0 item 2):* prediction 2's `bi + 1` cannot reach unchecked
throughput, since a `bigint` column is unbounded; the prediction is scored on a widened int,
`CAST(i AS BIGINT) + 1`, against its LEGACY twin.

## 7. Risks

1. **The lattice's saturation rule.** `cannotOverflow` compares against
   `Int.MaxValue` today. Widening that comparison without making it
   lane-dependent would take the check off a long op that can overflow - a
   wrong-answer bug, not a performance one, and the reason 3.2 calls it out
   rather than treating the lattice as width-free.
2. **A long literal reaching an int accessor.** `magnitude`'s
   `IntUnaryOperator` truncates silently if a long literal index is fed through
   it. The two tables must not be able to be confused; separate accessors rather
   than one widened.
3. **Option B's conservatism becoming invisible.** A pre-check that declines too
   often looks like correct-but-slow, and only a decline-rate assertion will
   show it. If B is chosen, it needs one.

## 8. Sequencing

1. **This PR:** the plan. No code.

1b. *Added 9 November 2026 (0 item 1), a pull request of its own after the plan's:* the row
path's baseline, `VarkaBigintArithmeticBaselineBenchmark` and its queries suite. Its sections,
each without and with one row in ten null: `l + 1`, `l + l2`, `l - l2`, `l * 3`, `l * m`, `-l`
and `abs(l)` under LEGACY and ANSI; `try_add(l, l2)`, `try_subtract(l, l2)` and
`try_multiply(l, m)`; over the bounded long leaf of 0 item 2, `CAST(i AS BIGINT) + 1` and
`CAST(i AS BIGINT) * 1000` under LEGACY and ANSI and as `try_add` and `try_multiply`, the
sections step 2 changes; the filter `l + l2 > 0` under ANSI; and the control `l ^ l2`, which
fuses today, pinned to ANSI. The data never overflows, so ANSI times arithmetic and not errors:
`l` and `l2` below `2^49`, the factor `m` in `[-3000, 3000]`, the `INT` `i` over the whole int
range, so `CAST(i AS BIGINT) * 1000` is below `2^41`. Every projection is `NONE` at both widths
but the control, `WHOLE` and checked for its kernel; the filter `NULL_TESTS`, or `NONE` where the
session refuses a 64-bit filter condition (VARKA-503). The results at both widths come from the
EPYC runners and are committed before it merges.

2. The lattice's long half, with its own tests and no compiler change - the
   check-removal machinery before anything that uses it.
3. The compiler's `+`, `-` and negate arms in all three modes, which need no
   emitter change at all.
4. The multiply: option A first, then B and C built and measured against it,
   the default chosen from the numbers.
5. The int32 checked multiply through VARKA-28's widening, once 28 has landed -
   which closes VARKA-30.

## 9. Outcome

*To be written when the work lands, section by section as the plan's own rule
asks. Nothing above is to be rewritten to look prescient.*

### 9.1 Steps 2 and 3, and option A of step 4, 10 November 2026

Built in one pull request, stacked on step 1b's baseline; not yet measured.

* **The lattice's long half** (`VarkaRangeAnalysis`): the long lane is answered for `INT` with no
  contract. A long literal is its point, read from the compiler's long table through
  `Literals.longs`, a separate accessor from the int one (risk 2); a widened int is its child's
  interval where that fits int32 and the int range otherwise; a long `GuardedRange` is its bounds.
  A bigint column, a decimal word and a hull stay unknown. The saturation rule is the lattice's
  own: an interval operation that leaves the `long` answers unknown, so at the long lane a check
  comes off exactly where the candidate's range is bounded (`VarkaNodeCompiler.cannotOverflow`),
  and at the int lane it is still `<= Integer.MAX_VALUE` (risk 1). The rules are proven in
  `range_analysis.smt2`, each with the case past the long that shows the bound is needed (5.7).
* **The arms**: `+`, `-` and `*` over `BIGINT` take the int arms' `intArith` on the long lane,
  negation and `abs` an `IntNeg` whose mode is `FAIL` under ANSI unless the lattice rules out
  `Long.MIN_VALUE`. `l + 1` keeps its check, `CAST(i AS BIGINT) + 1` and
  `CAST(i AS BIGINT) * 1000` lose it (5.2, corrected by 0 item 2). The checked multiply is option A:
  it fuses where the bound clears it and declines otherwise, so `l * 3` under ANSI stays on the
  row path; B and C are step 4's remaining work. ANSI `abs` needed the one emitter change 0 item 5
  predicted, at `VarkaVectorWalk.emitIntNeg`.
* **The two-lane hosts (0 item 7)**: the width audit's construction rows at 128 bits record the
  checked long add, `try_add` and the checked negation's overflow mask (the long compare, the mask
  `or`/`and`, the mask test and broadcast) as not supported by C2 at two long lanes, the Java
  fallback, while the wrapping long add and the checked int add record nothing. So under the setting row 503 keys its filter refusal on, a checked `BIGINT` operation
  in a projection declines, and the wrapping forms stay fused; the projection compile now takes
  `VarkaAdmission`, as the predicate compile did. The refusal is keyed on the `BIGINT` arms, not
  on the IR, so row 339's checked `TIMESTAMP_NTZ` difference and sum, which make the same mask,
  still fuse at two lanes unmeasured; that is a finding for a row of its own. Whether the checked
  forms beat the row path there anyway, as 503's blends did, takes a 128-bit run of the baseline
  benchmark with `spark.sql.codegen.varka.declineTwoLaneConditions=false`: with the refusal on,
  every checked section is the row path on both arms.
* **The baseline's classes** (`VarkaBigintArithmeticBaselineBenchmark`, as merged with 1b's
  review sections): at the host's width every section is `WHOLE` but the three checked products
  over a column (`l * 3` and `l * m` under ANSI, `try_multiply(l, m)`), which stay `NONE`. That
  includes the filter `l + l2 > 0` under ANSI, `NULL_TESTS` in the baseline: its condition now
  fuses, so the section the runner timed at 0.7X of the row path at 256 bits is a whole-kernel
  filter here. On a
  two-lane host the filter and every form that keeps its check are `NONE`; the wrapping forms and
  the widened int's six, whose checks the lattice took off, stay `WHOLE`.
* **The census**: `BIGINT` joins `+`, `-`, `*` (bounded only), `negative`, `abs`, `try_add` and
  `try_subtract`; 104 is off the division lines (`div`, `%`, `mod`, `pmod`, `try_mod`), which are
  row 30's (0 item 9).
* **The fuzzer** draws no long-lane `abs`: the draw reshuffles the long fuzz stream, which the
  emit cost model's committed tables are fitted on, so `VarkaEmitterLongLaneSuite` tests long `abs`
  over the extremes instead.

### 9.2 The runner measurement, 11 November 2026

`VarkaBigintArithmeticBaselineBenchmark` ran on the pull request's head (`814a8346416`) on the
EPYC 7763 runners three times: at 256 bits (vecbricks/varka run 42930745045), at 128 bits with
`-XX:MaxVectorSize=16` (run 42930757311), and at 128 bits with
`spark.sql.codegen.varka.declineTwoLaneConditions=false` as well (run 42930769718). The first two
replace the runner results files whole, since every section's fusion class changed; the third is
recorded here only. The ratios are the Varka arm's rate over the row path's, from one run each, so
a gap of 10 to 15% between runs is noise: the wrapping forms, the same code in the two 128-bit
runs, differ by that much.

* **256 bits.** Every projection that fuses whole is 4.2x to 9.4x the row path. The filter
  `l + l2 > 0` under ANSI, now a whole-kernel filter, is the weakest at 1.56x without nulls and
  1.65x at one row in ten null, against the baseline's 0.74x and 0.80x with only its null tests
  fused. The three checked products over a column (`l * 3` and `l * m` under ANSI,
  `try_multiply(l, m)`) decline and tie at 1.00x.
* **128 bits, as shipped.** The wrapping forms and the widened int's six fuse at 3.8x to 8.7x;
  every form that keeps its check declines and ties (0.98x to 1.04x). One wrapping form is the
  exception: `abs(l)` under LEGACY fuses whole but reaches only 1.32x without nulls and 1.43x with
  them, against 7.2x and 8.4x at 256 bits. The likely reason, from the source and not yet confirmed
  by a JIT reading: its default lowering is `x.blend(x.neg(), x.compare(LT, 0))`
  (`VarkaVectorWalk.emitIntNeg`), a long-lane mask, the construction C2 does not lower at two
  lanes. No section is slower than the row path.
* **128 bits, refusal off.** The checked forms fuse and the result is mixed. `try_add(l, l2)` and
  `try_subtract(l, l2)` reach 2.4x to 2.6x at both null densities; ANSI `l + l2` and `l - l2` 2.1x
  and 2.2x without nulls but 1.16x and 1.17x with them; ANSI `l + 1` 1.62x and 0.96x; ANSI `-l`
  1.43x and 0.77x; ANSI `abs(l)` 0.94x and 0.64x; and the filter 0.44x and 0.53x. So the refusal is
  right for the filter and for ANSI negation and `abs`, and leaves a win unclaimed on the `try_`
  sums and on null-free ANSI `+` and `-`. A refusal finer than the `BIGINT` arms' is a finding for
  a row of its own, not this one's.
