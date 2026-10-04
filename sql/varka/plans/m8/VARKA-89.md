# VARKA-89: the year-month interval divisions

*Written 18 September 2026, after VARKA-88 step 2 landed the double-lane division
this task is the first caller of. Section 2.20 of `m5/PLAN.md` scoped it
on 9 September 2026, split out of VARKA-68 by that task's admission check.*

## 1. Where this came from

VARKA-68 was given eight year-month interval expressions and found that two of
them divide by a constant: `extract(YEAR | MONTH FROM ym)` and `ym / num`. Both
divide a **stored month count**, which is an int32 with no bound on it at all,
and the emitter's only division at the time was the range-narrowed
Granlund-Montgomery magic the calendar prefix uses. For `/12` that magic is
`MONTH_ARITH_M`, exact over `0..49,151` - about one forty-thousandth of the
type - so the expressions could not be emitted and VARKA-68 deferred them.

Section 2.20 therefore sequenced this task after whichever of VARKA-65 (widen to
int64) and VARKA-88 (convert to double lanes) won, and said it "takes its division
from that". VARKA-88 step 2 landed on 18 September 2026 and is the route: a
conversion through double lanes, exact for every dividend the int lane can hold,
with no magic constant, no correction carry and no range guard.

## 2. The admission check, done

`sql/varka/plans/verify_ym_division.py`, run here, exhaustive where it claims to
be. Two independent things have to hold and they fail for different reasons, so
they are checked separately.

**The quotient.** `IntervalUtils.getYears(months)` is `months / 12` - Java's
`/`, which truncates toward zero. Over all 4 294 967 296 month counts, both the
divide and the reciprocal forms reproduce it exactly.

**Truncation is free, and the plan expected it not to be.** Section 2.20
anticipated "a truncation correction on the negative side". That correction is
owed by a *floor*-producing magic multiply, which is what the emitter has; it is
not owed here, because `D2I` is defined as the `(int)` cast and truncates toward
zero already. So the lowering is the conversion and nothing else - no correction,
no guard, no carry.

**HALF_UP is the correction this task does owe.** `ym / num` is
`IntMath.divide(months, num, RoundingMode.HALF_UP)`, which rounds half *away from
zero* - neither `rint` (half to even) nor any lanewise rounding the Vector API
offers. From the truncating quotient `q` and remainder `r = m - d*q` the rule is
"increment `q` away from zero iff `2*|r| >= |d|`", written as Guava's own
`|r| - (|d| - |r|) >= 0` so that a divisor near the type's extremes cannot
overflow the comparison. Checked over all int32 at `d = +/-12` and over windows
at zero and both ends of the range for every divisor in the list.

**The reciprocal's deny-list is not empty, which the first draft of the script
got wrong.** The `EXPECTED_RECIP_ADMITS` table was first written as "every
divisor admits it", and the run refused that: `d = 49` gets **40 848 894 of its
87 652 394 multiples wrong**, the textbook case where `fl(1/49)` rounds down far
enough that `49 * fl(1/49)` is `0.9999999999999999` and the first multiple
truncates to zero. The closed form now has a measured confirmation beside it, for
the divisor it refuses and for the one `extract(YEAR)` uses.

### 2.1 What is not a division, and blocks for another reason

`extract(MONTH FROM ym)` is `(months % 12).toByte`. The remainder is one multiply
and one subtract from the quotient beside it, so the arithmetic is not what stops
it: the result is a `ByteType`, and Varka has neither a byte lane nor an Arrow
vector to store one into. Section 2.20 already said the byte output is its own
question, and it stays open.

## 3. The design

**A new IR node, `ConstDivide(child, divisor)`.** The IR had no division node at
all before this - every division in the emitter was inside the calendar family,
reached through `emitDivide` and gated `requireInt` - so the first expression to
divide outside that family needs one.

The node is emitted through the double route **whatever
`VarkaEmitOptions.division` says**. That option chooses among the lowerings the
calendar has; this node has exactly one, so the option cannot turn it off, and a
setting that did would produce a kernel that computes nothing rather than one
that computes something slower. It is always the true divide: the reciprocal is
admissible only per divisor, by the closed form above, and choosing between them
is a performance question this node does not have to answer to be correct.

**What it does not need.** No overflow mode, no validity join, no carry, no range
guard. Dividing by a non-zero constant other than `-1` cannot overflow and cannot
null a valid lane, so the node is its child's validity word exactly - structurally
identical to `IntNeg`, which is what every emitter arm mirrors.

**The two refusals.** A zero divisor is refused in the constructor: a division by
zero raises rather than producing a value, and raising is the row engine's job
through the ghost fallback. A `-1` divisor is refused in the analysis: it
overflows at `Integer.MIN_VALUE`, the one input where Java's `/` throws rather
than answering, and Guava throws there too.

### 3.1 Registered op counts

A `ConstDivide` is VARKA-88's seven-op sequence and nothing else: two `I2D`, two
divides, two `D2I` and one `or` to rejoin the lane-disjoint halves. A divisor of
1 emits none of them, because the identity is the identity and a conversion round
trip for it would be a silent cost on a shape that does nothing.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/verify_ym_division.py` | the admission check |
| `VarkaVectorIR.java` | the `ConstDivide` node, its lane rule and its two refusals |
| `VarkaLoopEmitter.java` | `emitConstDivide`, and `Divider` carrying the species independently of the calendar's lowering choice |
| `VarkaValueRange.java` / `VarkaRangeAnalysis.java` | `divideBy`, the interval rule |
| `VarkaExpressionCompiler.scala` | `extract(YEAR FROM ym)`, and `extract(MONTH FROM ym)` declining on its output type |
| `VarkaIrGrammar.scala` | the fuzzer's production |
| `VarkaCoverageSuite.scala` | the two new rows, and the matched-only-to-decline exemption |
| `sql/varka/emitted_bytes.json`, `coverage.json`, `docs/sql-varka.md` | regenerated; see 5 |

## 5. What the oracle says, and why it was regenerated

`VarkaEmittedBytesSuite` reports exactly two kinds of movement, and the
distinction is the whole point of having it:

* **four new rows** - `extract(YEAR FROM ym)` and `extract(YEAR FROM ym) - 1`, at
  both widths, which are new coverage and have no previous bytes to move;
* **every fuzz block, at both widths** - because the fuzzer's grammar gained a
  production, so the pseudo-random draw shifts and every tree after the first
  decision point is a different tree. Nothing about the emission changed.

**No existing coverage row moved at either width.** That is the claim worth
making: all 57 rows the oracle pins are byte-identical, so nothing that compiled
before compiles differently now.

## 6. Tests, and what each is for

1. **The script**, committed and run, with its verdicts pinned so a change fails
   the run.
2. **The parity matrix at every width**, over the extremes of int32 and both
   signs: catches a quotient that is inexact at the ends, and a lowering that
   floors instead of truncating - which would show up only on a negative dividend
   with a remainder.
3. **The division option cannot turn the node off**: emitted through the double
   lane under all three settings.
4. **The two refusals**, each asserted where it is raised.
5. **A divisor of one emits no conversion.**
6. **The compiler arms**: `extract(YEAR FROM ym)` fuses over all three interval
   units, and `extract(MONTH FROM ym)` declines with the reason naming the byte
   output rather than the division.
7. **The fuzzer**, which now draws the node, against the reference evaluator -
   whose arm is Java's own `/`, so the two agree only if the lowering is right.
8. **The end-to-end differential**, both new rows against the row engine under
   both consumers.

### 6.1 The coverage guard gained a category

`VarkaCoverageSuite` required every expression class the compiler matches to have
a row in the table, and the table's rows are *fused* shapes. `extract(MONTH FROM
ym)` is a third thing: matched only so that the decline names the real blocker
instead of reading as "unsupported expression". It is now listed in
`matchedOnlyToDecline` with what blocks it, and a test asserts each entry really
does decline - so the list cannot become a place to park work that has since
landed.

## 7. What this task does not do

* **`ym / num`**, which needs the HALF_UP correction the script verifies here. It
  is its own node and its own tests, and it is the follow-up.
* **`ym / col`**, which declines: the divisor is not a constant, there is no
  lanewise integer divide, and the shape has no lowering.
* **`extract(MONTH FROM ym)`**, blocked on a byte output.
* **The YEAR-ended interval casts.** `CAST(ym AS INTERVAL YEAR)` is `(v/12)*12`
  and `CAST(ymy AS INT)` is `v/12`; both are the same division and both still
  decline with the reason `VarkaExpressionCompilerSuite` already pins. They are
  the natural next callers of this node and belong with `ym / num`.

## 8. Outcome

Landed 18 September 2026. `extract(YEAR FROM ym)` is the first expression Varka
fuses that no magic multiply could have served, which makes its benchmark
baseline a decline rather than another lowering - the row `VARKA-88.md`
section 6 registered as "one row only the double route can serve at all". The
throughput pair section 2.20 asks for lands with that benchmark.
