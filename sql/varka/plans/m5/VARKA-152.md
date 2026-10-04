# VARKA-152: the `TIME` split form, priced before any engine exists

## 1. Where this came from

`m8/SCOPE.md` item 11 argues that which physical form a value lives in
should be a compiler decision, and on 19 September 2026 it named the first case
that needs no engine to test: a `TIME` held as `(seconds of day: int32,
nanoseconds within the second: int32)` instead of Spark's nanoseconds of day in
a 64-bit lane. `VARKA-102.md` 2.5 asks the same question from the other
side - the `TIME` extracts compute in 64-bit lanes and produce 32-bit results
that no store narrows, so `hour`, `minute` and `second` wait on a narrowing
store or on VARKA-28's mixed-width kernels - and the split form would answer it
by changing the representation instead: the extracts become 32-bit divisions
of a number under 86400, and no kernel is mixed-width at all.

Item 11 is a long argument with no number under it. This task puts the first
number there, as a committed benchmark and nothing else: no conversion node, no
compiler arm, no cache encoding. Per the house rule the baseline lands as its
own PR before anything that would improve on it, so a later split-form kernel
shows up as a diff in this file rather than as a claim.

## 2. What is measured

`VarkaTimeBenchmark`, a file of its own in the catalyst benchmark scope, on the
same rungs as `VarkaLongLaneBenchmark` (16384, 262144, 1000000 and 8388608
rows) because the two forms read and write different bytes per row and a single
row count would price a cache boundary. Per rung, four shapes - `hour`,
`minute`, `second`, and the three together - each in four arms on the same
instants:

1. **nanoseconds of day, int64 lanes, conversion form**: the shipped lowering,
   `ConstDivide` through the double lane, three operations per division;
2. **nanoseconds of day, int64 lanes, magic form**: the same tree emitted with
   `useAVX = 2`, fourteen operations per division, which is what every
   AVX2-only runner in `VARKA-62.md` 11's census emits;
3. **seconds of day, int32 lanes, emitted**: the split form's extracts as the
   emitter lowers an int-lane `ConstDivide` today - the double route, seven
   operations per division, since `ConstDivide` has no magic multiply;
4. **seconds of day, int32 lanes, hand-written magic multiply**: the split form
   as item 11 imagines it, one multiply and one logical shift per division,
   exact over the bounded dividend. The constants are found by search and
   proven by exhaustion at start-up (`magic(3600, 86400)` and
   `magic(60, 3600)`), and the arm is checked against the definition on every
   rung's rows before it is timed.

The trees are built the way a compiler with common subexpressions would build
them: each field's remainder feeds the next, so the three-field shape divides
three times in the long form and twice in the split form (`second` is a
remainder there), and `minute` alone divides twice in both.

Beside the shapes: **the split itself** - one 64-bit division by 10^9 and a
multiply-subtract, both halves stored as 64-bit outputs because the narrowing
store does not exist, so it is an upper bound on what the conversion costs -
and two **floors**, a copy of each column, which is the memory cost of each
lane with no arithmetic.

## 3. Predictions, registered before the run

Committed with the benchmark and before the first regeneration.

1. **`hour` in the split form beats the long conversion form by 1.5x to 2.5x**
   under today's int lowering, at every rung: twice the lanes per register and
   half the bytes per row, against seven operations where the long form has
   three. At the DRAM rung the ratio settles near the byte ratio, 2x.
2. **The hand-written magic multiply beats the long conversion form by at
   least 2.5x on `hour` at the in-cache rungs**, and converges toward the byte
   ratio at the DRAM rung, where arithmetic stops being the bound.
3. **The magic form costs the long lane at least 2x against the conversion
   form on `hour` in cache** - fourteen operations against three - and the
   gap narrows at the DRAM rung. This is the number the AVX2 half of the
   runner pool pays today.
4. **The split costs between 1.3x and 2x of one long `hour`** - the same
   division plus a multiply, a subtract and a second wide store - so splitting
   once and then extracting pays only when two or more fields are taken from
   the same column, or when the split is stored (the Arrow cache's second
   encoding).
5. **Three fields together: the split emitted form beats the long conversion
   form by at least 2x in cache**, two divisions against three at half the
   width, and the hand-written arm by at least 4x.

## 4. Files

* `sql/catalyst/src/test/scala/org/apache/spark/sql/VarkaTimeBenchmark.scala`
* `sql/catalyst/benchmarks/VarkaTimeBenchmark-jdk25-results.txt`,
  `-128bit-results.txt`, `-provenance.txt` - written by
  `dev/varka_bench_regen.sh catalyst VarkaTimeBenchmark`
* `dev/varka_bench_ids.sh` - the new file in the default list
* `m5/PLAN.md` row 152 and section 2.88; `m8/SCOPE.md`
  item 11's `TIME` row points here once the numbers exist.

## 5. What this task does not do

No engine, no node, no cache encoding: the numbers decide whether those are
worth building. The hand-written arm is a reference, not a lowering - the
emitter's `ConstDivide` at the int lane stays the double route until a task
gives bounded int-lane divisions a magic multiply, and that task's first line
will be this file's rows 3 and 4 side by side.

## 6. Outcome, 20 September 2026

Regenerated on the laptop by `dev/varka_bench_regen.sh catalyst
VarkaTimeBenchmark` with the canary flat (compute +0.1%, cache -2.5%, memory
-0.3%). Rates in M rows/s from the committed files; the arms are numbered as in
section 2.

### 6.1 The wide run (512-bit species: eight 64-bit lanes, sixteen 32-bit)

| rung | shape | 1 conversion | 2 magic | 3 split, emitted | 4 split, hand-written |
|---|---|---:|---:|---:|---:|
| 16384, both in L2 | `hour` | 4204.3 | 3525.0 | 3839.7 | 24417.3 |
| | `minute` | 2118.4 | 1327.4 | 1888.4 | 21529.6 |
| | `second` | 1407.3 | 706.4 | 1835.5 | 16532.8 |
| | all three | 1391.8 | 684.5 | 1801.0 | 13298.7 |
| 262144, both in L3 | `hour` | 4164.5 | 3605.0 | 3877.5 | 18466.0 |
| | `minute` | 2109.6 | 1364.0 | 1886.6 | 17667.1 |
| | `second` | 1401.3 | 718.6 | 1846.9 | 16719.4 |
| | all three | 1379.6 | 694.6 | 1764.8 | 8189.4 |
| 1000000, the long form leaves L3 | `hour` | 4093.2 | 3512.9 | 3924.4 | 19609.8 |
| | `minute` | 2117.2 | 1357.3 | 1913.0 | 19586.7 |
| | `second` | 1404.7 | 713.8 | 1874.0 | 15634.8 |
| | all three | 1242.9 | 702.9 | 1823.5 | 5083.9 |
| 8388608, both past L3 | `hour` | 2535.1 | 2395.5 | 3827.0 | 6033.1 |
| | `minute` | 1999.3 | 1323.1 | 1895.2 | 6006.0 |
| | `second` | 1380.4 | 711.0 | 1861.2 | 5973.8 |
| | all three | 1005.2 | 693.2 | 1700.2 | 2032.2 |

The split itself: 3989.3, 3882.7, 2493.9 and 1466.6 down the ladder. The
floors: the 64-bit copy 12118.3, 9570.4, 6642.2, 2583.9; the 32-bit copy
23043.6, 17764.0, 18983.3, 5860.5.

### 6.2 The predictions scored against the wide run

**Prediction 1 is wrong in cache and right only where memory decides.** The
split form under today's int lowering does not beat the long conversion form
on `hour` at any in-cache rung: 0.91x, 0.93x, 0.96x. It beats it only at the
DRAM rung, 1.51x, which is the byte ratio arriving. The reason is arithmetic
the prediction did not do: an int-lane `ConstDivide` converts each half of the
sixteen lanes to a double vector and divides *twice*, so per row it performs
exactly the double divides the long lane performs once over eight lanes - the
same `vdivpd` throughput per row, plus the split and the join. Halving the
bytes and doubling the lanes bought nothing because the divider, not the
width, is the bound. **The split representation is worth nothing under the
double route.**

**Prediction 2 holds, and understates it.** The hand-written magic multiply is
5.8x, 4.4x and 4.8x the conversion form on `hour` in cache, and at the DRAM
rung 2.4x - the byte ratio, where it runs at the 32-bit copy floor (6033.1
against 5860.5). On `minute` and `second` it is 10x to 12x in cache, because
those shapes carry two and three divisions in the long form and one multiply
each here.

**Prediction 3 is wrong on `hour` and right by `second`.** The magic form
costs the long lane only 1.19x on `hour` in cache (3525.0 against 4204.3), not
the 2x fourteen operations against three would suggest; on `second` and the
three-field shape, three divisions each, it is 2.0x. One `vdivpd` has enough
latency that a single division's fourteen cheap operations hide behind it;
three back to back do not. So the AVX2 half of the runner pool pays a fifth
more for `hour` today, and twice for `second`.

**Prediction 4 is wrong on the range and right on the consequence.** The split
costs about one long `hour` - 0.95x of it in cache, 0.58x at the DRAM rung
where its second wide store shows - not 1.3x to 2x. A split done once is
cheaper than any single long extract, and so is not the obstacle; what the
split buys is the obstacle, per prediction 1.

**Prediction 5 is wrong on the emitted arm and right on the hand-written
one.** Three fields together: the split emitted form is 1.29x the long
conversion form in cache, not 2x - two divisions against three, at the same
divides per row. The hand-written arm is 9.6x, 5.9x and 4.1x in cache and 2.0x
at the DRAM rung, where three 32-bit stores against three 64-bit ones is again
the byte ratio.

### 6.3 The narrow run (128-bit species: two 64-bit lanes, four 32-bit)

| rung | shape | 1 conversion | 2 magic | 3 split, emitted | 4 split, hand-written |
|---|---|---:|---:|---:|---:|
| 16384 | `hour` | 2377.2 | 48.8 | 2135.0 | 30855.0 |
| | `minute` | 1177.3 | 22.3 | 768.5 | 12199.6 |
| | `second` | 763.5 | 15.0 | 749.1 | 8389.1 |
| | all three | 760.3 | 14.9 | 743.0 | 8384.9 |
| 262144 | `hour` | 2381.3 | 46.2 | 2138.6 | 19140.2 |
| | `minute` | 1182.0 | 21.9 | 769.3 | 11593.1 |
| | `second` | 773.0 | 14.6 | 748.6 | 8288.1 |
| | all three | 746.0 | 14.7 | 743.6 | 6474.9 |
| 1000000 | `hour` | 2331.6 | 45.8 | 2122.7 | 19407.3 |
| | `minute` | 1178.0 | 21.5 | 766.1 | 11661.8 |
| | `second` | 754.1 | 14.4 | 745.8 | 8194.8 |
| | all three | 729.4 | 14.5 | 739.0 | 4508.1 |
| 8388608 | `hour` | 2200.8 | 45.0 | 2072.0 | 5970.2 |
| | `minute` | 1164.9 | 21.3 | 760.1 | 5890.4 |
| | `second` | 755.6 | 14.3 | 744.6 | 5844.3 |
| | all three | 707.3 | 14.2 | 731.3 | 1811.7 |

The split itself: 2258.9, 2237.7, 1935.7, 1423.1. The floors: the 64-bit copy
14867.5, 8078.4, 6083.9, 2417.7; the 32-bit copy 27722.5, 18273.0, 19213.4,
5849.3.

Three things the narrow file says that the wide one does not.

**The magic form collapses at 128-bit lanes: 48.8 M rows/s on `hour` against
the conversion form's 2377.2, one fiftieth, and 14 to 22 on the shapes with
more divisions.** Those are per-lane rates, not vector ones: something in the
fourteen-operation sequence has no 128-bit lowering on this JVM and C2 replaces
the operation with a Java loop over the lanes - the failure mode the form's own
javadoc names as its reason to avoid `abs()`, reached by another operation.
The wide run cannot see it because at 512 bits every operation in the
sequence has an encoding. Section 6.5 has the diagnosis from the JVM's own
log, and the 256-bit companion says whether the width real AVX2 hosts run at
is affected.

**The conversion form holds at the narrow width** - 2377.2 against 4204.3 on
`hour`, 0.57x for half the lanes - which means the `L2D`/`vdivpd`/`D2L` route
is the one lowering of the 64-bit division that is safe at every width this
machine can be asked for. At 128 bits the split emitted form is again no
faster than it (0.90x on `hour`, 0.65x on `minute`), for the reason 6.2 gives.

**The hand-written magic multiply is 13x the conversion form on `hour` at the
L2 rung and 8x at the L3 and 1M rungs**, more than at the wide width, because
two 64-bit lanes leave more of the divider's latency exposed than eight do.
At the DRAM rung it is the byte ratio again, 2.7x, at the 32-bit copy floor.

### 6.4 What the numbers decide

1. **The split representation pays only with a bounded int-lane magic
   multiply, and then it pays 4x to 13x in cache and the byte ratio out of it.**
   Under the emitter's existing int-lane `ConstDivide` it pays nothing,
   because that lowering divides in double lanes exactly as often per row as
   the long lane does. So the representation change and the lowering are one
   decision, not two: a `TIME` split leaf whose seconds are known to lie in
   `[0, 86400)` is what licenses a single multiply and shift for `/3600` and
   `/60`, the way the calendar prefix's narrowed ranges license its magic
   multiplies today. Neither half is worth building without the other.
2. **The split itself is cheaper than any one long extract**, so it is not
   what the decision turns on. Stored in the Arrow cache as a second encoding
   it costs nothing per query; computed per batch it costs about one `hour`.
3. **The 64-bit magic form is not a lowering to select by AVX level.** It buys
   at most nothing at 512 bits on this machine (0.84x on `hour`), costs 2x on
   three divisions, and collapses at 128 bits. VARKA-88's `useAVX` switch stays
   opt-in, which it is; what an AVX2-only host at 256 bits does is 6.5's
   question, and the answer decides whether the form has any host at all.

### 6.5 The 128-bit collapse, diagnosed from the JVM's own log

The magic-form `hour` kernel alone, under `-XX:MaxVectorSize=16
-XX:+UnlockDiagnosticVMOptions -XX:+PrintIntrinsics`. C2 refused, for the
emitted class's loop and epilogue bodies:

```
** not supported: arity=1 opc=410 vlen=2 etype=long ismask=0 is_masked_op=1
** not supported: arity=2 opc=384 vlen=2 etype=double ismask=0 is_masked_op=1
** not supported: arity=2 op=comp/3 vlen=2 etype=long ismask=usestore
** not supported: arity=2 op=comp/1 vlen=2 etype=double ismask=usestore
** not supported: arity=0 op=broadcast vlen=2 etype=long ismask=1
```

That is every masked step of the form and every compare that feeds one: the
masked `NEG` that takes the magnitude (`opc=410`, a masked unary over long
lanes), the masked `SUB` that rounds the floor (`opc=384`, a masked binary over
double lanes), the two compares whose masks they consume, and the mask
broadcast. At `vlen=2` - two 64-bit lanes, the 128-bit species - this JVM has
no vector lowering for a masked long or double operation or for a
compare-to-mask, and each falls back to the Vector API's Java implementation, a
loop over the lanes. The unmasked steps (`reinterpret`, the adds, the
multiply) are fine, which is why the rate is a fiftieth and not a thousandth.
The conversion form has no mask in it and holds at 2377.2.

This is wider than one lowering. Anything the emitter builds at the long lane
from a compare and a masked operation - the range guard's condemning mask,
the checked add's overflow test, a blend over a 64-bit comparison - meets the
same refusal at two lanes, and a NEON-only aarch64 host runs at two 64-bit
lanes with no override at all. `m5/PLAN.md` 2.89 carries that as a
task: the audit is one `PrintIntrinsics` run per long-lane shape at
`MaxVectorSize=16`, and the assembly gate's 128-bit half is where it belongs.

### 6.6 The 256-bit companion: the collapse is two lanes, not "narrow"

Because the 128-bit result depends on the width, the file earned a 256-bit
companion (`dev/varka_bench_regen.sh catalyst VarkaTimeBenchmark --width=32
--narrow-only`, the `VARKA-144.md` 9.4 precedent), four 64-bit lanes and
eight 32-bit ones - the species every AVX2 host runs at:

| rung | shape | 1 conversion | 2 magic | 3 split, emitted | 4 split, hand-written |
|---|---|---:|---:|---:|---:|
| 16384 | `hour` | 4581.7 | 2968.1 | 4215.1 | 36328.2 |
| | all three | 1462.7 | 443.3 | 1413.4 | 14602.5 |
| 262144 | `hour` | 4692.5 | 3011.0 | 4168.5 | 19570.3 |
| | all three | 1455.6 | 440.9 | 1413.6 | 9553.0 |
| 1000000 | `hour` | 4355.4 | 2965.2 | 4186.8 | 19324.8 |
| | all three | 1200.2 | 444.5 | 1421.1 | 5130.7 |
| 8388608 | `hour` | 2606.0 | 2554.0 | 4061.4 | 6052.8 |
| | all three | 998.6 | 442.5 | 1351.2 | 1962.6 |

At four lanes the magic form is a vector form again: 2968.1 on `hour`, 0.65x
of the conversion form, and 443.3 on three fields, 0.30x. So the refusal is
`vlen=2` specifically, and an AVX2 host at 256 bits does not meet it. What
this companion cannot say is what the conversion form costs on such a host:
here the converts intrinsify at 256 bits because the machine has AVX-512VL,
where an AVX2-only machine has no vector `L2D`/`D2L` at all and the magic form
is its only vector lowering (`VARKA-88.md` 9.2). That number is VARKA-121's,
on the runner that lacks the converts, and until it exists the magic form's
standing is: the only vector form on AVX2 hosts, a 1.5x to 3x loss where the
converts exist, and a per-lane loop at two lanes.

The provenance file records the last regeneration, which was this companion;
the 128-bit file carries its own header with the run that produced it.

## 7. What this leaves

* **The split form is a task the moment VARKA-102 group C is built**, and it
  arrives as one change with two halves: a `TIME` leaf split into
  `(seconds, nanos)` - in the Arrow cache as a second encoding, or per batch as
  one long division - and a bounded int-lane `ConstDivide` that lowers to a
  single multiply and shift when the analysis proves the dividend under the
  divisor's exact range. `magic(d, range)` in the benchmark is the search that
  lowering needs, and rows 3 and 4 of this file are its before and after.
* **VARKA-153** (`m5/PLAN.md` 2.89): the audit of every long-lane
  masked construction at 128 bits, with the assembly gate's 128-bit half as
  its home.
* **VARKA-121** still owns the AVX2 runner's numbers for the conversion form
  against the magic form; this file gives it the shapes and the ids.
* **Item 11's engine question is not advanced by this**, on purpose: the
  measurement says a representation choice can be worth an order of magnitude
  and that the choice is inseparable from a lowering the representation
  licenses, which is an argument for making the form explicit on IR values
  (item 11's first design input) and not yet for an extractor.
