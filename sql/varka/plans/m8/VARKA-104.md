# VARKA-104: `Long` arithmetic, VARKA-30's int64 half

*Written 18 September 2026. Section 2.39 of `m5/PLAN.md` opened this
task on 15 September 2026 by the milestone's re-scope, and adjusted it on 17
September: comparisons over a `bigint` column landed with VARKA-29, and this task
keeps every arithmetic form - `bi + 1` included - because the overflow-mode
decision is its subject.*

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

**B has an AVX-level caveat that A and C do not.** `dev/varka_canary/L2DProbe.java`
records that under `-XX:UseAVX=2` the long-to-double converts fail to inline and
the loop degrades to scalar. So B is a good default at AVX-512 and possibly a bad
one at 128-bit, which makes it exactly the kind of choice this project measures
at both widths rather than picking once.

**A is the floor** and ships first regardless: it is the existing mechanism, and
3.2 is what makes it reach anything at all.

### 3.4 Division, modulo and `pmod`

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

| file | what |
|---|---|
| `VarkaRangeAnalysis.java`, `VarkaValueRange.java` | the long half of the lattice, and a saturation rule that is lane-dependent rather than int32 |
| `VarkaExpressionCompiler.scala` | the four long arithmetic arms, the long literal table reaching the lattice |
| `VarkaLoopEmitter.java` | only the multiply, and only if 3.3 picks B or C; A needs no emitter change at all |
| `VarkaReferenceEvaluator.scala` | the checked-multiply arm at `long`, which throws today |
| `VarkaIrGrammar.scala` | long arithmetic productions for the fuzzer |
| `VarkaCoverageSuite.scala` | the `bigint` arithmetic rows |

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
