# VARKA-89: the year-month interval divisions

*Written 18 September 2026, after VARKA-88 step 2 landed the double-lane division
this task is the first caller of. Section 2.20 of `m5/PLAN.md` scoped it
on 9 September 2026, split out of VARKA-68 by that task's admission check.*

**Written for `extract(YEAR FROM ym)` and landed on 18 September 2026. Section 0 (21 October 2026) checks it against what is on master now and overrides sections 3, 4 and 7 where they differ; the row's remaining work is the list in 0.5, not the one section 7 gives.**

## 0. Admission check against master (21 October 2026, `a429af749f4`)

The plan was written when the emitter's only division was the calendar's magic and the IR had no
interval or width machinery beyond the int lane. Since then rows 28, 104, 149, 281, 334, 335a, 379
and 39 have landed. What the plan assumed, against the code:

### 0.1 What is stale in sections 3 and 4

| the plan says | master has |
| --- | --- |
| `ConstDivide(child, divisor)` | `ConstDivide(child, divisor, dividendBound)` (`VarkaVectorIR`); the two-argument constructor is the int lane's form and refuses a `LONG` child. The int lane's bound is its own (`INT_DIVIDEND_BOUND`). `quotientBound()` derives the bound a division hands the next one. |
| "emitted through the double route whatever `VarkaEmitOptions.division` says"; "VARKA-88's seven-op sequence" | `VarkaDivisionLowering.emitConstDivide` chooses among three forms. At the int lane the default is the multiply-high through 64-bit lanes (`emitMulHiDivide`, VARKA-149, taken when `VarkaEmitOptions.mulHiDivide()` is on and the divider can convert); the double conversion is the reference arm behind that option off. At the long lane it is the double conversion, or `emitMagicDivide` on a host whose converts do not intrinsify (`takesMagicDivide`). The option `division` is still ignored by this node. `extract(YEAR FROM ym)` therefore emits the multiply-high form today, which `VarkaEmitBudget` weighs at eleven lane operations at the int lane, not seven. |
| `emitConstDivide` and `Divider` in `VarkaLoopEmitter.java` | `emitConstDivide` is in `VarkaDivisionLowering.java`; `Divider` is the species carrier it reads as `analysis.divider`. The node's cost is priced in `VarkaEmitCostTable` (`ConstDivide/INT/pos`, `/neg`, `/LONG/pos`, `/LONG/neg`), refit by row 335a. |
| the compiler arm in `VarkaExpressionCompiler.scala` | the arm is in `VarkaIntervalCompiler.java` (`case ExtractANSIIntervalYears`, `case ExtractANSIIntervalMonths`); the compiler was ported to Java (VARKA-175, 214, 217). The fuzzer's production is `VarkaIrGrammar.java` (test), and `VarkaCoverageSuite.scala` is unchanged in kind. |
| "zero divisor refused in the constructor; `-1` refused in the analysis" | still exact: `ConstDivide`'s compact constructor throws on 0 and on `Long.MIN_VALUE`; `Analysis` (`case ConstDivide n`) throws on -1 and on a width with no double species. A caller that builds one must therefore refuse 0 and -1 itself, before the node (0.3). |
| "no overflow mode, no validity join, structurally identical to `IntNeg`" | still true: `Analysis` gives `ConstDivide` its child's word (`wordOwner`, `pureWord`), and `VarkaRangeAnalysis` ranges it by `VarkaValueRange.Range.divideBy`. |
| "the IR has no remainder node" (implicit in 2.1) | still none. `VarkaTimeCompiler.remainderOfSixty` builds `x - (x / 60) * 60` from `IntArith`, which is the precedent for `extract(MONTH)`'s `v % 12`. |

### 0.2 What already exists that this row did not have

* **Width conversion** (row 28): `WidenLane` and `NarrowLane`, exact on the int range; a kernel
  holding both lanes is driven at the long lane. The section 2 HALF_UP correction does not need
  it (0.4), but a long-lane form of it could now be built.
* **Range guards** (rows 39 and 379): `GuardedRange(child, lo, hi)` serves both lanes and declines
  the batch outside the bound; `ConstDivide`'s own javadoc names it as the way a caller discharges
  a long-lane dividend bound. Nothing in the year-month family needs it, because every int-lane
  dividend is under `INT_DIVIDEND_BOUND`. A year-month column is never bounded
  (`VarkaRangeAnalysis` bounds date columns only), so `ym * 2` still declines on its checked
  multiply (`VarkaIntervalCompiler.multiply`) and the divisions below must not depend on a bound.
* **Branch-free blends**: `Compare`, `IfElse` and `IntArith` with `WRAP` are all the HALF_UP
  correction needs, and `VarkaIntervalCompiler.absolute` already builds `IfElse(Compare(LT, x, 0),
  IntNeg(x), x)` over an interval operand.
* **The census** (`results/VARKA-376-census-*.json`): `int.I2L`, `long.L2I`, `int.I2D` and
  `double.D2I` are intrinsic (verdict `I`) at 128 and 256 bits on the EPYC 7763 and at 128 bits on
  the Neoverse N2; `long.L2D` and `double.D2L` are `F` (fallback) on the EPYC at both widths. So
  an int-lane division, which is all this row's current work uses, is the intrinsic path on every
  host; a long-lane one is not on AVX2, which is why `takesMagicDivide` exists.

### 0.3 What is on master for each division this row names

| shape | today | by what |
| --- | --- | --- |
| `extract(YEAR FROM ym)` | **fused** over all three units, `ConstDivide(col, 12)` | `case ExtractANSIIntervalYears`; `VarkaExpressionCompilerSuite` pins the shape; coverage rows for it and for `- 1` |
| `extract(MONTH FROM ym)` | declines, "returns a byte, which has no lane" | `case ExtractANSIIntervalMonths`; listed in `VarkaCoverageSuite.matchedOnlyToDecline`. Row 329 (byte lanes) is not built: no plan file, and no byte lane in the emitter, so the blocker is unchanged. `census.tsv` lists it under 329. |
| `ym / num`, integral `num` | **not matched**; generic "unsupported expression" decline (`DivideYMInterval` has no arm and no test names it) | none |
| `CAST(ymy AS INT)` | declines generically; only the MONTH-ended direction is an arm | the comment above `case Cast ... MONTH_INTERVAL` says it "belongs with the year-month extracts" |
| `CAST(ym AS INTERVAL YEAR)` (narrowing) | declines, "year-month interval narrowed to a YEAR-ended unit, which divides by twelve" | pinned in `VarkaExpressionCompilerSuite` ("the unit relabel type coercion inserts is admitted, the truncating one not") |
| `ym / col`, `ym / fractional`, `ym / decimal` | declines, by absence | a column divisor is row 30's, a fractional one 342's, a decimal one 349's (`census.tsv`: `89,342,349`) |

`DivideYMInterval` is also built by `Average` over year-month intervals (`Average.scala`: the
`DivideYMInterval(sum, count)` replacement), where `count` is a column; that case is `ym / col`
and stays declined whatever this row does.

### 0.4 Which steps still hold, and the corrected ones

* **Step: `extract(YEAR FROM ym)` through a stored-count-safe division.** Done, and still exact; the
  admission script `plans/verify_ym_division.py` is unchanged and its verdicts hold for the quotient
  at any lowering, since the int-lane multiply-high is exact over the whole lane (its constructor
  checks Theorem 5.1). Section 3's *route* (double conversion) is replaced by the three-form
  choice above, with no change in contract. **Stale only as a description.**
* **Step: the HALF_UP correction for `ym / num` ("from `q` and `r = m - d*q`, increment away
  from zero iff `2|r| >= |d|`").** The rule holds and is what `IntMath.divide(.., HALF_UP)`
  does. What changes is the lowering and the guards the plan did not list:
  1. Spark raises before dividing: `divideByZeroCheck` on `num == 0`, and
     `checkDivideOverflow` on `months == Int.MinValue && num == -1`, in every mode
     (`DivideYMInterval.nullSafeEval`). The compiler must refuse a literal 0 (the `ConstDivide`
     constructor would throw otherwise, and the row engine must raise) and handle -1 as a
     negation that declines on `Int.MinValue` (`negationMode`'s FAIL), or decline it.
  2. For a literal `d` with `|d| >= 2` the whole correction stays on the int lane, with no
     widening and no guard: `q = ConstDivide(m, d)`, `r = m - q*d` (`IntArith` `WRAP`; `|q*d|
     <= |m|`, so it cannot overflow), `h = ceil(|d| / 2)` (at most `2^30` for an int `d`, so it
     fits the lane), and the quotient moves one step away from zero iff `|r| >= h`, in the
     direction `sign(m) * sign(d)`. That is two compares, a blend and an add; `q + 1` cannot
     overflow, since `|q| <= 2^30` for `|d| >= 2`. This replaces the plan's remark that the
     correction needs Guava's `|r| - (|d| - |r|) >= 0` form, which exists to avoid overflow in a
     runtime divisor and has no work to do against a constant.
  3. **This is a proposal, not a measured fact.** The script verifies the rule over all int32 at
     `d = +/-12` and over windows for the list; the step is not done until it is extended to the
     int-lane form above, exhaustively over int32 for each divisor the compiler admits, and
     pinned.
  4. A long literal `num` takes `LongMath.divide` and a divisor outside the int range quotients
     every month count to 0 or +/-1; decline it in the first cut, with the reason, and take only
     `Integer`, `Short` and `Byte` literals (the implicit cast gives `num` its own type, so the
     arm reads `num.dataType` first, as `multiply` does).
* **Step: `ym / col`, the fractional and decimal factors.** Not this row. The plan's section 7
  already says so; `census.tsv` now names the owners (30, 342, 349), which settles it.
* **Step: the YEAR-ended casts.** Still the natural next callers. The inbound
  `CAST(ymy AS INT)` is `ConstDivide(v, 12)` exactly (`IntervalUtils.yearMonthIntervalToInt`:
  `v / MONTHS_PER_YEAR` for a YEAR end field), which is the same node `extract(YEAR)` builds, so it
  is an arm and a test. The narrowing `CAST(ym AS INTERVAL YEAR)` is `periodToMonths(
  monthsToPeriod(v), YEAR)`, i.e. `(v / 12) * 12`: `ConstDivide` then an `IntArith` `MUL` `WRAP`
  by a twelve slot. It cannot overflow, since `|(v / 12) * 12| <= |v|`, so unlike `yearsToMonths`
  it needs no bound. The plan's section 7 says both "decline with the reason
  `VarkaExpressionCompilerSuite` already pins"; only the narrowing is pinned there, and the
  inbound cast declines generically.
* **Step: `extract(MONTH FROM ym)`.** Still blocked on a byte output, now by row 329, which has no
  plan file yet. When it lands the arm is `m - (m / 12) * 12` through `IntArith`, with the sign
  of `m` (Java's `%`, the same as `x - q*12` over a truncating quotient), narrowed to the byte
  lane. Do not build it before 329; keep it in `matchedOnlyToDecline`, whose test already fails
  if it starts to fuse.

### 0.5 What the row is, after the check

The row's "Done when" is no longer "`VARKA-89.md`'s list holds" with nothing in the list: it is

1. `ym / num` for a literal integral `num` (`Integer`, `Short`, `Byte`), fused by the int-lane
   HALF_UP form in 0.4, with the three refusals (zero, -1 at `Int.MinValue`, a long literal) each
   pinned and the row engine's error class compared for the first two;
2. `CAST(ymy AS INT)` and `CAST(ym AS INTERVAL YEAR)`, fused as `ConstDivide` and
   `ConstDivide` times twelve, with the existing narrowing decline test turned into a fusion test;
3. the exhaustive int32 verification of the HALF_UP form, extending `verify_ym_division.py`;
4. coverage rows for each, regenerated, not edited; the benchmark pair is its own pull request;
5. `extract(MONTH FROM ym)` stays with row 329.

No new IR node, emitter arm or evaluator change is expected: all of it composes `ConstDivide`,
`IntArith`, `Compare` and `IfElse`. If the build finds it needs one, say so in the pull request.

### 0.6 What was run

`dev/varka_emit.sh "extract(YEAR FROM ym)" "extract(YEAR FROM ym) - 1" --columns ym:ym` on
a429af749f4 (the laptop's preferred species, default options): both fuse; the IR is
`(divc:12 col:0)` and `(int:SUB:FAIL (divc:12 col:0) lit:0)`; the dense loop has 12 `IntVector`, 4
`LongVector` and 4 `convert` call sites. Four conversions are two `I2L` and two `L2I`, which is
the multiply-high form of `emitMulHiDivide`, not the double route section 3 describes (that would
show `DoubleVector` sites; the column reads 0). The `SUB` is marked `FAIL` in the IR and still
fuses, because `divideBy` bounds the quotient; that is the range analysis working over the
node, as 0.1 says. Not run: `ym / num`, the YEAR casts (nothing matches them yet), and any
other width or option.

### 0.7 What was not checked

* The shape each unbuilt expression would emit; every statement about them comes from reading the
  code and the census files.
* The cost of the HALF_UP form against the row engine and against the row engine's own
  `IntMath.divide`: unmeasured, and the plan registers no number.

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
