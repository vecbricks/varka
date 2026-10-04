# VARKA-119: the oracle for the long lane - the fuzzer and the bytes oracle at `long`

## 1. Where this came from

`m5/PLAN.md` 2.54 opened this task on 15 September 2026 when VARKA-29's
validation was read: it said every parity gate would re-run at the long species
and nothing said who taught the *oracle* long semantics. The first part landed
with VARKA-85 - `VarkaReferenceEvaluator.evalLong` and `evalCondLong` over the
lane-generic subset, and `VarkaLoopEmitterSuite.checkLongMatrix` driving curated
long shapes through the eight-argument `run` against them (`VARKA-85.md`
9.4). The rest was re-sequenced to land with the nodes it checks: VARKA-88's
64-bit constant division in both its lowerings, VARKA-102's range guard and the
`TIME` arms built from them.

Those landed (#255, #258) with hand-written tests only. `VARKA-102.md` 6.1
says so in as many words: "the grammar still generates no `LONG` node and the
new guard is fuzzed only at the int lane." The most intricate code in the
emitter - the magic-number divide's floor, magnitude and sign tail, the range
guard's status word, the long literal table - was covered by five curated
shapes per lowering and nothing random. VARKA-151 (#261) had just shown what a
reasoned "it agrees" is worth when nothing random is under it, and the next
long-lane kernel (VARKA-103's intervals) was about to be built on the same
footing. So this task went first.

## 2. What the oracle already had, and what it did not

**The evaluator was complete.** `evalLong` has arms for the two leaves, the
arithmetic in its three modes, the negate, `ConstDivide`, `GuardedRange`, the
hull ops and the conditional, and `evalCondLong` for the five conditions. That
is the whole lane-generic node set, because 2.54's expectation of "`TIME`'s
day-modular arithmetic and the interval overflow checks" did not become nodes:
VARKA-102 lowered `t + dt` as a range guard over a wrapping add over a guarded
multiply, with the overflow a declined batch rather than a modulus (the
SPARK-57853 reading), and `t - t2` as a constant division over a subtraction.
Every `TIME` and interval expression the compiler admits is a tree of nodes the
evaluator already spells, so a grammar over the node set covers them without
knowing their names. No evaluator arm was added.

**Three things were missing.**

1. The fuzz grammar drew int32 trees only, so `ConstDivide` at 64 bits,
   `GuardedRange` at 64 bits and every `TIME` arm were fuzzed not at all.
2. The fuzzer's "reaches every node type" assertion had no long-lane
   counterpart, so a node the long grammar could not build would be a silent
   gap rather than a failure.
3. The emitted-bytes oracle pinned no long-lane emission: its schema had no
   `bigint`, `TIME` or interval column, so the twenty long rows of the coverage
   table were recorded as `coverage_rows_skipped` - the file said what it did
   not pin, as designed, and what it did not pin was the whole second lane.

## 3. The design

### 3.1 A second corpus, not long shapes in the first

`VarkaIrGrammar.fuzzSeed`'s sequence is shared with `VarkaEmittedBytesSuite`,
which pins ten thousand of its shapes by block digest. Drawing long shapes into
that sequence - one more arm in `Shapes.value`'s `rnd.nextInt(23)` - would
reshuffle every block and the oracle would lose the ability to say whether the
int32 emitter changed (`sql/varka/skills/emitter-and-ir.md`, "Adding an IR node
moves the bytes oracle's fuzz digests"). So the long lane is a second sequence:
`longFuzzSeed`, `drawLongShape`, `LongShapes`, all beside the int ones in
`VarkaIrGrammar`, and the int corpus is untouched - not one committed digest of
it moved.

### 3.2 `LongShapes`

The generator draws over the lane-generic node set only, with the bound
discipline the int grammar taught:

* **Columns within plus or minus 2^46, literals within 2^20.** A nanosecond of
  day is a little over 8.6e13, so 2^46 is the magnitude the `TIME` kernels see;
  a sum of two columns stays under 2^47 and a product saturates.
* **`ConstDivide` only where the dividend bound is under
  `EXACT_DIVIDEND_BOUND`**, the long lane's `fitsUnderChrono`. The bound is a
  precondition nothing enforces (VARKA-147); a dividend past it makes the
  conversion form off by one and the magic form read the dividend modulo 2^52,
  and neither is a bug this suite is asking about. The divisors are the ones the
  `TIME` and interval lowerings divide by - nanoseconds per unit, micros per
  day - plus small ones on both signs, where truncation and floor part on every
  second or third dividend rather than once in a billion.
* **A checked mode only under an unsaturated bound**, so the kernel answers and
  the zero-status assertion has something to check; the multiply is WRAP only,
  since no 64-bit overflow test exists (VARKA-104).
* **A guard that contains the child's bound** - `(-bound, bound)`, or the whole
  long range when the bound is saturated - because a guard that fires declines
  the batch and the reference evaluator has no spelling for a decline. The
  guards' firing is asserted in the emitter suite.

### 3.3 The reach test asks the constructors

The int lane's reach test asserts against the sealed hierarchy: every record
node type must be built. At the long lane the target set is smaller - the
calendar nodes refuse a 64-bit child - and a hand-written list of the
lane-generic ones would be exactly the kind of table that drifts. So the test
classifies each node type by what its own constructor does: `admitsLongLanes`
builds one instance over long leaves (a long column for every IR component, a
long comparison for every `Cond`, first enum constants and small numbers for
the rest) and reads a `requireInt` refusal as "int only". A lane-generic node
added to the IR is in the long lane's reach set the day it lands and fails the
test until `LongShapes` builds it. The probe checks itself against two facts the
emitter's javadoc states - `Year` refuses, `ConstDivide` admits - so a change
to the constructors' behaviour that broke the classification would be noticed.

### 3.4 The fuzzer at the long lane

`runOneLong` is `runOne` over 64-bit buffers and the eight-argument `run`, with
`evalLong` as the oracle and the same draws for length, null pattern, forced
masking and `VarkaEmitOptions`. Two points carry over on purpose: null lanes
are poisoned with `Long.MinValue` and `Long.MaxValue`, alternating on the null
ordinal, for the reason `testing-and-debugging.md` gives - every drawn value is
inside every guard and checked mode by construction, so only a poisoned null
lane can reach a condemning comparison; and `randomOptions` is shared, which is
what puts `useAVX` under the constant division and fuzzes both of its lowerings
on one machine. `-Dvarka.fuzz.iterations`, `-Dvarka.fuzz.seed` and
`-Dvarka.fuzz.only` apply to both lanes.

### 3.5 The bytes oracle pins the second lane

Two changes. The oracle's schema gains the coverage suite's six long columns
(`l`, `l2`, `t`, `t2`, `dt`, `dt2`), so the twenty long rows compile and are
pinned by name rather than listed as skipped. And a `fuzz_long` sequence sits
beside `fuzz` under each width: ten thousand shapes from `drawLongShape` at
`longFuzzSeed`, one digest per block of a hundred, with the same difference
report. The width keys stay `4` and `16` and mean 128 and 512 bits: a long-lane
shape is emitted at 2 and 8 of its own lanes, since sixteen 64-bit lanes would
be a species that does not exist and the emitter would fall back to
`SPECIES_PREFERRED` and pin nothing about width.

## 4. Files

* `sql/catalyst/src/test/.../varka/VarkaIrGrammar.scala` - `longFuzzSeed`,
  `longColumnBound`, `longLiteralBound`, `DrawnLong`, `drawLongShape`,
  `LongShapes`.
* `.../varka/VarkaIrFuzzSuite.scala` - `runOneLong`, `admitsLongLanes`, the two
  long-lane tests; the int reach test refactored onto the shared helpers.
* `.../varka/VarkaEmittedBytesSuite.scala` - the six long columns, the
  `fuzz_long` sequence, the width mapping at the long lane.
* `sql/varka/emitted_bytes.json` - regenerated: the long rows leave
  `coverage_rows_skipped` and gain hashes, `fuzz_long` blocks appear; no
  existing hash moves (section 6).
* `sql/varka/skills/testing-and-debugging.md`, `emitter-and-ir.md`,
  `SKILLS.md` - the lessons in section 7.

## 5. Verification

1. `VarkaIrFuzzSuite` at the default 300 iterations, both lanes, both reach
   tests.
2. Ten thousand iterations at the JVM's own width and under
   `-XX:MaxVectorSize=16` (128 bits), the row's done-when.
3. `VarkaEmittedBytesSuite` regenerated, and the JSON's flattened keys diffed
   against `HEAD`: the expected difference is additions only - twenty coverage
   rows per width, two `fuzz_long` block lists, the emptied skip list - and no
   committed hash changed.
4. A deliberately wrong evaluator arm caught by the fuzzer: `evalLong`'s
   `ConstDivide` arm changed to `Math.floorDiv` for one run, which must fail on
   a negative non-multiple dividend within the default iterations.

## 6. Outcome

### 6.1 What the long-lane fuzzer found on its first run: the harness

The first long shape of the corpus declined its batch, null-free, in 33
milliseconds. The shape was fine; the harness's value draw was not:
`rnd.nextLong() % (2 * bound + 1) - bound` lands in `[-3 * bound, bound]` for a
negative draw, so a third of the column sat three bounds below zero, outside
the guard the grammar had drawn to contain it. `Math.floorMod` fixed it. The
int harness never had the problem because it clamps. It is recorded because it
is the shape of every first fuzz failure: a decline in a null-free batch is the
harness's problem before it is the emitter's.

### 6.2 What the ten-thousand-iteration run found: the int grammar

With the long lane clean at 300 iterations, the row's done-when was run - ten
thousand iterations at the JVM's own width - and the **int** sequence failed at
iteration 847 of the committed seed, a shape nothing had drawn before because
the default stops at 300:

```
(guardedRange (truncDate:YEAR col:0) -2500000 2500000)
```

The batch was declined, correctly. `Shapes` gave `TruncDate` its child's bound,
with the note that trunc "moves a date down by at most a year, so the child's
bound holds". That was true for the only consumer the bound had until VARKA-102: the calendar placement, which checks against `chronoBound` with a factor
of two in hand. VARKA-102's range-guard arm (`case 22`, #258) then took the
bound as the range the value must stay in, and a date in the last year below
the column bound truncates to a year-start below `-2500000`: a live lane the
guard condemns. `boundsOf` and both `TruncDate` arms now add 366, the way
`LastDay` adds 31 and `NextDay` adds 8, and iteration 847 passes. The same
shape fails on `master` at the commit this branch started from, so it is
#258's, not this task's - a grammar bug that produces a *correct* decline,
which is why nothing but the fuzzer's status assertion could have found it.

The moving int32 fuzz digests this causes are the grammar change's, not an
emission change's, and section 6.4 checks that.

### 6.3 The runs

| run | int lane | long lane |
|---|---|---|
| 300 iterations, default width | clean | clean |
| 10000 iterations, default width (512-bit species) | clean, 11 s | clean, 6 s |
| 10000 iterations under `-XX:MaxVectorSize=16` (128-bit species) | clean, 12 s | clean, 5 s |
| iteration 847 of the int seed, after the bound fix | passes | - |

The `lanesOverride` draw puts every baked width from 2 to 32 lanes under both
runs as well; the JVM flag changes what `SPECIES_PREFERRED` and the unbaked
emissions see.

### 6.4 The bytes oracle's regeneration, checked by key

The regenerated `emitted_bytes.json` diffed against `HEAD` by flattened key:

* **Changed: four keys.** `description`, `coverage_rows_skipped` (twenty rows
  to none), and `lanes/4/fuzz/blocks` and `lanes/16/fuzz/blocks`, in which 24
  of 100 blocks moved at each width. Those are the int shapes whose `TruncDate`
  bound grew by 366 (6.2): a wider guard is a different constant in the emitted
  method, and a subtree that now fails `fitsUnderChrono` where it passed before
  is a different shape. The oracle's own report named the same 48 blocks.
* **Added: 404 keys**, the twenty long coverage rows at both widths with every
  method hashed, and the two `fuzz_long` block lists.
* **Removed: none. No coverage hash changed**, at either width, which is the
  statement that matters: the long lane was added to the oracle and the int
  grammar corrected without the emitter's output moving for any named shape.

The regeneration before the bound fix had changed two keys only (`description`,
`coverage_rows_skipped`) and added the same 404, so the fuzz-block movement is
the bound fix's alone.

### 6.5 A wrong evaluator arm is caught

With `evalLong`'s `ConstDivide` arm changed to `Math.floorDiv` for one run, the
long-lane test failed at iteration 4 of the default 300 - `23411 did not equal
23412`, a negative dividend of `-60` - and the evaluator was restored. The
corpus distinguishes truncation from floor within its first handful of shapes,
which is what the small negative divisors were put in the divisor list for.

## 7. What this leaves

* **The dividend bound stays unenforced (VARKA-147).** The grammar keeps every
  dividend under `EXACT_DIVIDEND_BOUND` by construction, so the fuzzer cannot
  find a violation of a precondition nothing checks. When 147 adds the per-batch
  guard, the grammar should draw over the bound and assert the decline, the way
  the emitter suite asserts the range guards' firing today.
* **No checked multiply at either lane (VARKA-104)** - the grammar draws `MUL` as
  WRAP only, at both lanes.
* **`VarkaRangeAnalysisSuite` has no long counterpart**, and needs none while
  the analysis answers UNKNOWN for every non-int lane; the day it answers at
  the long lane, `LongShapes` is the generator to ask it over.
* **The nightly** (`dev/varka_nightly.sh`) runs `VarkaIrFuzzSuite` with a
  varying seed and iteration count, so it now varies both lanes' corpora with
  no change of its own.
