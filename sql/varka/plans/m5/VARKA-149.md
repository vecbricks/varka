# VARKA-149: the int-lane division is divider-bound, and the lowering that pays is a multiply-high

## 1. Where this came from

`m5/PLAN.md` 2.85 opened this task on 19 September 2026 from VARKA-88
step 4's own A/B: `extract(YEAR FROM ym)`, whose only lowering is
`ConstDivide`'s conversion through double lanes, read 3897.6 M rows/s against a
scalar loop's 3003.5 at 512-bit lanes - 1.30x, where every other lowering in
the file is several times a scalar loop - and 2102.7 against 2857.7 at 128
bits, **0.74x**: a fused kernel slower than a plain loop over the same buffers,
on the width every NEON-only aarch64 host runs at. The section asked where the
form's seven operations stop paying at four lanes, and whether to decline the
shape below a width or find the lowering that pays there.

## 2. The admission check: the first lead was wrong, and the measurement said so

VARKA-153's audit (#264) gave this task a lead. Under `PrintIntrinsics` the
kernel prints `** missing constant: vclass=DecodeN etype=ConP vlen=LoadI
bitwise=ConI` at every width, alone or in sequence, and no int-lane shape
without a division prints it at the host's own width. The line is a
`fromBitsCoerced` - a broadcast - and the conversion form divides with the
scalar convenience `DoubleVector.div(double)`, which broadcasts its scalar on
the receiver's own species, a field of a vector whose exact type C2 may not
know after a half-width `convertShape`. A one-line fix followed: broadcast the
divisor from the species constant the emission holds and divide by the vector.

Built, tested and measured on 20 September 2026, it changed nothing. The
bytes moved in exactly the seven coverage rows that carry a `ConstDivide` and
in every fuzz block, the suites stayed green, and `VarkaTimeBenchmark`'s
`seconds of day, int32 lanes, emitted` arm - the int conversion form on its
own - ran within noise of the committed rows at all four rungs (3839.7 to
3924.4 M rows/s at 512 bits, `VARKA-152.md` 6.1) under a filtered run
whose output is not committed because filtered runs never are. The change was
reverted before this plan was written: bytes do not move for a measurement
that did not.

What the null result says is what VARKA-153's own classification said: a
`missing constant` line is a first late-inline attempt that C2 retries after
more optimisation, and here the retry succeeds. The lesson is recorded there
(`sql/varka/skills/the-jit.md`); this plan is the case that proved it on the
shape that motivated the doubt.

## 3. The arithmetic: why the form is slow, from the numbers already committed

The conversion form's cost per lane group is fixed by its two double divides,
not by the converts around them. At sixteen 32-bit lanes it converts two
halves of eight, divides each half in a 512-bit `vdivpd`, converts back and
ORs: two divides per sixteen rows. At four lanes it does the same two divides
over two lanes each: two divides per four rows. A vector double divide's
throughput is a property of the divider unit and barely improves at narrower
widths, so the form's cost per row is roughly four times higher at 128 bits
than at 512 - which is what the committed rows show, 3897.6 against 2102.7 -
while the scalar loop it is measured against is nearly flat, 3003.5 against
2857.7, because it does not divide at all: C2 strength-reduces `x / 12` into a
multiply-high and two shifts, an integer sequence that costs about the same
per row on any machine. `VarkaTimeBenchmark` says the same thing from the
other side: the int form runs at the long form's rate per row at every rung
(`VARKA-152.md` 6.2), because both issue one double divide per eight
lanes. The seven operations are not where the cost is; the two divides are.

So there is nothing to fix inside the conversion form, and the choice 2.85
named is real: decline the shape below 256 bits, or give the int-lane
`ConstDivide` the lowering the scalar loop already has.

## 4. The design: a multiply-high through 64-bit lanes

A signed 32-bit division by a constant has a single-multiply form over the
whole int range - Granlund and Montgomery's, Hacker's Delight 10-6: for a
divisor `d` there are a magic `M` and a shift `s` with `q = (mulhi(x, M) + x
if M < 0 as signed) >> s`, then `q += (x >>> 31)` to correct toward zero for
negative dividends. C2 emits exactly this for `x / 12`. The Vector API has no
32-bit multiply-high, which is why the calendar's magic multiplies are
range-narrowed instead; but a 64-bit lane holds the full 32 x 32 product, and
the emitter already converts int halves to a wider lane for the double route.
The lowering is the same shape with long lanes in place of double ones:

1. `convertShape(I2L, LONG species, part)` - each int half widens to a long
   vector, sign-extended;
2. `mul(M)` - the 64-bit product, which fits: `|x * M| < 2^31 * 2^31`;
3. `lanewise(ASHR, 32 + s)` - the high word, already shifted by `s`;
4. `convertShape(L2I, INT species, part)` - narrow back, the two halves
   lane-disjoint as the double route's are;
5. `or` the halves, then the sign correction: `add(lanewise(LSHR, 31))` on the
   int lane, and the `+ x` term before the shift where `M` is negative as
   signed, which is a long-lane `add` of the widened dividend before step 3.

Nine to eleven int-and-long lane operations, no divide, no double lane. The
exactness argument is the classical one and is proven the way this project
proves such things: a committed script, `verify_int_magic_division.py`, that
derives `(M, s)` for every int-lane divisor the emitter and the fuzz grammar
use (`12`, and `2 3 7 100 -3 -12` from `VarkaIrGrammar`) and checks
`(x * M >> (32 + s)) + fix == x / d` over all 2^32 dividends, the sibling of
`verify_double_division.py`. The emitter derives `(M, s)` by the same
algorithm at emit time and refuses a divisor the script has not covered, so
the proof and the code cannot drift.

**What the machine has to support**, and the width audit (VARKA-153) will say
rather than the plan assuming it: a 64-bit lanewise multiply (`vpmullq` is
AVX-512DQ; below it C2 emulates `MulVL` from `vpmuludq` steps, which is
slower but a vector), the two conversions (`I2L` and `L2I` - the same casts
whose `L2D`/`D2L` cousins the audit found refused at four lanes on the AVX2
runner, so this is the first thing to check there), and a 64-bit arithmetic
shift. If the audit refuses any of them on a host class, the form declines to
the conversion route there, exactly as the magic form does today.

**Where it sits in the option space.** The conversion form stays as the
reference arm behind `VarkaEmitOptions.division`, so the A/B is one flag; the
multiply-high becomes the int-lane `ConstDivide`'s default if and only if the
numbers in section 5 hold at both widths. The long lane is unchanged: it has no
128-bit multiply to widen into, and its conversion form is exact and bounded
by the same divider.

**The bounded case is a second, cheaper step and not this task.** When the
range analysis proves the dividend under the divisor's exact range for a
single 32-bit multiply - the split `TIME`'s seconds under 86400 - no widening
is needed at all: one `mul`, one `LSHR`, which `VarkaTimeBenchmark`'s
hand-written arm measured at 4x to 13x the double route (`VARKA-152.md`
6.4). That is VARKA-102 group C's lowering and it shares the script.

## 5. Predictions, registered before the build

Anchors are the committed files: the parity file's `extract(YEAR FROM ym)`
block and `VarkaTimeBenchmark`'s int emitted arm.

1. **`extract(YEAR FROM ym)` at 512 bits at least doubles**: from 3897.6 to
   above 7800 M rows/s under the multiply-high, since the divider leaves the
   critical path and the form is bound by two 64-bit multiplies and the
   converts.
2. **At 128 bits it beats the scalar loop by at least 1.2x**: from 2102.7 to
   above 3400 against 2857.7. This is the row 2.85's done-when names.
3. **The int emitted arm of `VarkaTimeBenchmark` overtakes the long conversion
   form on `hour` in cache by at least 1.5x**, and stays at least 2x below the
   hand-written single multiply, which has no widening to pay.
4. **On the AVX2 runner the width audit reports `MulVL` lowered, not refused**,
   at four 64-bit lanes; if it is refused, the form declines there and this
   prediction is scored as the finding it is.
5. **The op-count test registers the form at no more than eleven lane
   operations per division**, against the conversion form's seven; the win is
   the divider, not the count, which is why 4 and 5 are stated separately.

## 6. Files

* `VarkaLoopEmitter` - `emitMulHiDivide` beside `emitDoubleDivide`, the magic
  derivation, and the selection rule; `VarkaEmitOptions.Division` gains the
  variant that names the reference arm.
* `sql/varka/plans/verify_int_magic_division.py` - the exhaustive proof.
* `VarkaLoopEmitterSuite` - op counts, `dividendsAround` at the int lane for
  every divisor in the script, both widths; the fuzzer covers the rest through
  its divisor list.
* `sql/varka/emitted_bytes.json` - the seven `ConstDivide` rows and the fuzz
  blocks move.
* `VarkaEmitterParityBenchmark` - the `extract(YEAR FROM ym)` block gains the
  multiply-high arm beside the conversion form and the scalar loop;
  `VarkaTimeBenchmark` regenerates.
* `m5/PLAN.md` 2.85 - the outcome; `docs/sql-varka.md` if the
  coverage note changes.

## 7. Sequencing

The script first, since it decides which divisors the form may take; the
emitter and its op-count test; the differential over `dividendsAround` and a
ten-thousand-iteration fuzz run, which draws the int divisors; the width audit
at 128 and 256 bits, and its CI run on the AVX2 runner; the bytes oracle; the
two benchmark regenerations; then section 5 scored and 2.85 closed. Size:
medium - a day, most of it the proof and the two regenerations.

## 8. What was built, 20 September 2026

**The lowering.** `VarkaLoopEmitter.emitMulHiDivide`, taken by an int-lane
`ConstDivide` whenever `VarkaEmitOptions.mulHiDivide` is on - the default -
and the width names a species to widen into, the same condition under which the
conversion form could convert. The dividend is parked in one scratch slot and
read three times: each int half widens with `I2L` into the long species of the
same width, multiplies by the unsigned magic, shifts right arithmetically by
`32 + s`, narrows with `L2I` into its own lanes; `or` rejoins the halves, the
dividend's sign bit (`LSHR 31`) is added, and a negative divisor multiplies the
result by -1. Eleven lane operations at most and no divide, as section 4
counted. `signedMagic(d)` derives `(Mu, 32 + s)` by Hacker's Delight 10-6 at
emit time, with the book's "add the dividend where the multiplier is negative"
folded into the unsigned multiplier, which a 64-bit product makes exact. The
long lane is untouched.

**The option.** `mulHiDivide` is the twenty-fourth component of
`VarkaEmitOptions`, on by default and rendered into `canonical()` like every
other, so the conversion form is one flag away as the reference arm and two
kernels differing only in it never share a shape hash. The fuzzer toggles it
at random with every other boolean, so both forms are fuzzed from the day it
lands.

**The proof.** The plan's exhaustive script became an opt-in test instead,
because the arithmetic under proof is Java's: `the multiply-high form is exact
over every int32 dividend for every divisor in use (-Dvarka.sweep=true)`
computes the emitted form as scalar longs for all 2^32 dividends against
Java's `/`, for `12`, the fuzz grammar's `2 3 7 100 -3 -12`, and the `TIME`
split form's `60` and `3600`. Beside it, the constants for `2 3 7 12 100` are
pinned to the book's values, the kernel is checked against `evalValue` over the
extremes and each divisor's multiples at five widths under both forms, and the
op counts of both forms are registered: the multiply-high has four long-lane
operations, four conversions and no double-lane one; the conversion form two
double halves and four conversions.

**The benchmarks.** The parity file's `extract(YEAR FROM ym)` block gains the
conversion form as a case beside the shipped multiply-high, both against the
scalar loop; `VarkaTimeBenchmark`'s seconds-of-day emitted arm becomes the
multiply-high and the double route stays beside it as a fifth arm. Both files
are regenerated at every width they carry.

## 9. Outcome, 20 September 2026

Regenerated on the laptop with every control flat (the parity file's scalar
loop 3003.5 to 3045.7, and the year rows within 1.3%), the TIME file at all
three widths, and the parity file at both.

### 9.1 The numbers

`extract(YEAR FROM ym)` in the parity file, M rows/s:

| width | multiply-high (shipped) | conversion form (was shipped) | scalar loop |
|---|---:|---:|---:|
| 512-bit | 9057.5 | 3908.7 | 3045.7 |
| 128-bit | 3330.8 | 2102.8 | 2851.5 |

`VarkaTimeBenchmark`'s seconds-of-day arms, `hour` and the three fields, at the
L2 rung:

| width | shape | long conversion form | int multiply-high (shipped) | int double route | hand-written single multiply |
|---|---|---:|---:|---:|---:|
| 512-bit | `hour` | 4246.8 | 9132.7 | 3922.4 | 25167.4 |
| 512-bit | all three | 1408.6 | 2978.9 | 1789.2 | 13086.3 |
| 128-bit | `hour` | 2383.8 | 3610.4 | 2149.0 | 29205.0 |
| 128-bit | all three | 765.6 | 1122.4 | 743.3 | 8304.1 |

### 9.2 The predictions scored

1. **Holds.** 2.32x at 512 bits, from 3897.6 to 9057.5, above the 7800 the
   prediction named.
2. **Holds on the done-when, misses the margin.** At 128 bits the kernel reads
   3330.8 against the scalar loop's 2851.5 - 1.17x, not the 1.2x predicted,
   and 1.58x its own conversion form. 2.85's done-when was a committed 128-bit
   row on which the kernel is not slower than a plain loop; it is now faster.
3. **Holds.** The int multiply-high overtakes the long conversion form on
   `hour` in cache by 2.15x at 512 bits and 1.51x at 128, and sits 2.8x
   below the hand-written single multiply at 512 bits, which pays no widening.
4. **Not yet scorable.** The width audit (VARKA-153, #264) is not on `master`
   as this is written, so no run of it has seen this form on the AVX2 runner.
   The catalyst shard that carries it will, on the first run after both merge;
   the prediction stands.
5. **Holds, at eleven.** Two widenings, two multiplies, two shifts, two
   narrowings, an or, a shift and an add, registered by the op-count test; the
   conversion form's seven stay registered as the reference arm's.

Two things the run said that the predictions did not ask. The long conversion
form did not move (4204.3 to 4246.8, within 1%), which is what leaving the
long lane untouched should produce and is the control for the TIME file. And
the int double route did not move either (3839.7 to 3922.4), which closes
section 2 a second way: the broadcast it was accused of costs nothing.

### 9.3 What this leaves

* **VARKA-121's AVX2 numbers, now for two forms.** The audit's first CI run
  found the 64-bit lane's converts refused at four lanes on the EPYC 7763; the
  multiply-high's `MulVL` at four lanes is prediction 4, and the same shard
  answers it.
* **The bounded single multiply**, `VARKA-102.md` 8.4's `BoundedDivide`:
  the hand-written arm is still 2.8x above this form where the dividend is
  known small, because it neither widens nor narrows. `signedMagic` is its
  unbounded sibling and the two share a family.
* **VARKA-148's weight** for the int-lane `ConstDivide` is now eleven
  operations against a registered one; the group budget still counts it as one.
