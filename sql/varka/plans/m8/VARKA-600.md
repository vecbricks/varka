# VARKA-600: No BIGINT to FLOAT cast on AVX2 slower than a scalar loop

## 1. Where this came from

Row 600 of `PLAN.md`, added on 11 November 2026 on the owner's word from #1476 (row 583, the
cheaper AVX2 conversion forms). Below emit level 3 a `BIGINT` to `FLOAT` cast fuses by
`VarkaConversionLowering.emitRoundToOdd`: the magnitude rounded to odd at the bit `2^12`, an
exact double and one `D2F`, 24 lane calls (`ODD_CALLS`), no mask. #1476 made it 17 to 22% faster
than 343b1's form, but on the EPYC 7763 runners it still loses to a plain Java loop of
`(float) v`. Row 583's done-when compared the new form with 343b1's only, so no row owned this
gap until this one.

## 2. The admission check, done

Read on master at `e5d70012bdc`, 20 November 2026. No laptop run was needed. The question is
whether the vector form still loses to the scalar loop on current code.

1. **The code is unchanged since the measurement.** `git log 9db413d4e13..HEAD` on
   `VarkaConversionLowering.java` and `VarkaWideConversionBenchmark.java` lists no commit.
   The default `L2F` is still the round-to-odd form with the logical-shift sign and `sel`
   (`roundToOddByShifts=false`).
2. **The gap, by the committed runner files.** In
   `sql/catalyst/benchmarks/VarkaWideConversionBenchmark-jdk25-runner-epyc7763-results.txt`
   (run 42894474041, 256 bits) the round-to-odd arm reads 1609.6 M rows/s in L2 against 2280.6
   for the scalar loop, and 1447.6 against 1826.2 past L3. In
   `VarkaWideConversionBenchmark-jdk25-runner-epyc7763-128bit-results.txt` (run 42894494552) it
   reads 846.8 against 2271.5 in L2 and 810.7 against 1833.7 past L3. The `-2-` twins of both
   repeat it within 1.5% an arm. So the form runs at 71% of the scalar loop in cache at 256 bits
   and at 37% at 128, as the row says. The problem holds.
3. **`L2D` is not the problem.** In the same two files
   (`VarkaWideConversionBenchmark-jdk25-runner-epyc7763-results.txt` and
   `VarkaWideConversionBenchmark-jdk25-runner-epyc7763-128bit-results.txt`) the XOR split reads
   5175.0 against the scalar loop's 2277.8 at 256 bits in L2, and 3555.6 against 2255.8 at 128.
   So an exact `L2D` over the lanes is cheap. The cost of `L2F` is the rounding to odd, about 16
   of its 24 calls.
4. **The bounded `L2D` is a warning.** The `MAGIC` arm, five calls behind a range guard, reads
   5470.5 at 256 bits but 40.3 at 128 bits in L2 (the same two files). Its guard is
   `VarkaVectorWalk.emitRangeGuard`, a long-lane compare to a mask. At two 64-bit lanes C2 on
   x86 lowers no mask (`VARKA-503.md` 1), so the guard runs as the Vector API's Java loop. Any
   form this row builds must test its window without a mask.
5. **No SQL-level number for the fused cast yet.**
   `VarkaFloatingCastBaselineBenchmark-jdk25-runner-epyc7763-results.txt` and its `-128bit`
   twin are from 31 October 2026 (commit `3d448173651`), before 343b1 fused the cast. Both arms
   ran the row path there. The class now lists `BIGINT CAST(l AS FLOAT)` as `WHOLE` where the
   long casts fuse. That query costs about 40 ns a row on either arm, most of it the scan and
   the row output, while the cast itself is about 1 ns a row in any kernel form here. So the
   fused query should be about as fast as the row path or a little faster (prediction 6), and
   the decline the done-when allows is not expected to trigger. But no committed file shows it
   yet.

## 3. The design

### 3.1 Two new forms behind one option

A new `VarkaEmitOptions` component, `narrowConversionForm` (enum `NarrowConversionForm`), picks
the `L2F` form where `convertsFallBack()` holds. `ROUND_TO_ODD` is today's form, kept as the
reference arm. `roundToOddByShifts` stays as its sub-variant. The option gets its
`VarkaEmitOption.TABLE` entry the day it lands, so the fuzzer and the option matrix draw it.

* **`WINDOWED`.** Per lane group, a test without a mask: `w = (v + 2^51) >>> 52` is zero on
  exactly the lanes with `-2^51 <= v < 2^51`, and `w.reduceLanes(OR)` is one scalar long. If it
  is zero, the group takes the bounded magic `bitsToDouble(v + bits(M)) - M`, exact on that
  window, then `D2F`, which is then the one rounding of `v` (the double is exact, so no double
  rounding). Otherwise the group takes the round-to-odd form. That is a scalar branch, not a
  batch decline. About 7 calls on the common path against 24. The window is `2^51`, not `2^53`,
  because the magic form is exact only there (`MAGIC_WINDOW`).
* **`SCALAR`.** The lanes converted one by one: `v.lane(k)`, `l2f`, then `FloatVector.withLane`
  into the result. That is 2 or 4 lane reads, conversions and inserts a group. It is Java's own
  cast, so it needs no proof. The extract and insert are intrinsics on AVX2 (**[I]**: to be read
  from `-XX:+PrintIntrinsics` in step 1). A spill through a scratch `long[]` and `float[]` is
  the fallback form if they are not. It risks a store-forwarding stall when four scalar stores
  are read back as one vector.

**Alternatives considered.** (a) A cheaper round to odd. A signed truncation with a sticky bit
saves the magnitude, but it still needs `sel` and the blend. By hand it comes to about the same
24 calls, so it is not built. (b) A per-batch pass over the stored column, like
`Analysis.guardsStoredValues` (VARKA-379). It covers only a bare column input. The calibration
table records a separate pass costing 9 to 17% of a cheap kernel (VARKA-379 6.1 #2). Rejected.
(c) Declining the cast below level 3 everywhere. It gives up the cast's share of the query, a
few percent end to end at best (2 item 5), and gains nothing where a kernel form beats the
scalar loop. Kept only as the done-when's last resort, per width, if a
baseline shows the fused cast losing.

**The default.** The owner's done-when is per width, and the measured winners may differ: two
lanes favour `SCALAR`, four may favour `WINDOWED`. The default is resolved from the drive lane
count the kernel is emitted at, which is already in the shape key. So no session reads the host
for it (question 10.1). The losing arms stay as named options (the house rule on machine-dependent
winners).

### 3.2 What is deliberately unchanged

* `L2D`: the XOR split wins at both widths (2 item 3).
* Level 3 and aarch64, where `L2F` is one instruction (`convertShape`, row 584).
* The `MAGIC` `L2D` arm's masked guard at 128 bits (2 item 4). It is not a default. Its guard could
  take `WINDOWED`'s test, but that is a separate change, recorded in section 9 when it is measured.

### 3.3 Registered op counts

From `dev/varka_emit.sh --table --variant narrowConversionForm=WINDOWED --variant
narrowConversionForm=SCALAR "CAST(l AS FLOAT)" "CAST(l + 1 AS FLOAT)"` at `useAVX=2` once
built, pasted here before the measurement. Expected by hand: `ROUND_TO_ODD` 24 calls (the
constant), `WINDOWED` about 7 on the taken path plus the 24 behind the branch, `SCALAR` about
3 calls a lane. `VarkaEmitBudget.weightOf` weighs `WINDOWED` at both arms, since both are in
the method.

## 4. Files

| file | what |
|---|---|
| `VarkaEmitOptions.java`, `VarkaEmitOption.java` | the option, its table entry, the default |
| `VarkaConversionLowering.java` | `emitWindowed`, `emitScalarLanes`, the call weights |
| `Slots.java`, `Analysis.java` | the scratch the forms park values in |
| `VarkaProofFiles.java`, `round_to_odd.smt2` | `WINDOWED`'s window test and exactness |
| `VarkaEmitterWideConversionSuite.java` | each form at the cells and binades |
| `VarkaWideConversionBenchmark.java` | the two arms, and a rung past the window |
| `emitted_bytes.json`, `width_audit.json` | regenerated |

## 5. Tests, and what each is for

* `VarkaEmitterWideConversionSuite`: each form against `(float) v` at the existing cells
  (`2^53 + 2^29 + 1`, the binade edges, `Long.MIN_VALUE`) at 128 and 256 bits. It catches a
  form that is exact on most values but not the double-rounding cases.
* The same suite, a group straddling the window: one lane at `2^51 - 1` and one at `2^51`, and
  a ragged batch whose epilogue holds the out-of-window lane. It catches `WINDOWED` taking
  the fast arm for a whole group when only some lanes fit.
* `round_to_odd.smt2`, new claims: `(v + 2^51) >>> 52 == 0` iff `v` is in the window, and on
  it `toFloat(bitsToDouble(v + bits(M)) - M) == toFloat(v)`. Priced at VARKA-245's ratio of
  about 0.8 for linear claims: under 2 s per solver.
* `VarkaIrFuzzSuite` draws the new option from its table entry; no new test.
* The width audit under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` at both widths: no `not supported`
  line for `reduceLanes`, `lane` or `withLane` at the long and float species.

## 6. The measurement

`VarkaWideConversionBenchmark` gains the arms `cast(l AS float), windowed` and
`cast(l AS float), scalar lanes`, named by form so the labels survive the default flipping. It
also gains a fourth rung of values spread up to `2^62`, where `WINDOWED` takes its slow branch
on every group. Run on the EPYC 7763 runners at both widths (two runs a width, compared by
minimums), dispatched by the owner. Then `VarkaFloatingCastBaselineBenchmark` is re-run at both
widths with the new defaults, for the SQL-level check of the done-when. Controls: the two
scalar-loop arms and the XOR split, which no change touches.

### 6.1 Predictions, registered before the run

1. `WINDOWED` beats the scalar loop at 256 bits in L2 and L3, by at least 1.3x. The basis is
   the bounded `L2D` at 5470.5 against 2277.8 for the scalar double
   (`VarkaWideConversionBenchmark-jdk25-runner-epyc7763-results.txt`), plus one `D2F` and a
   reduction.
2. At 128 bits `WINDOWED` is within 10% of the scalar loop, either side. The reduction and the
   branch are a larger share of a two-lane group.
3. `SCALAR` is within 15% of the scalar loop at both widths. It does the loop's work plus the
   lane moves.
4. Past the window, `WINDOWED` is no slower than `ROUND_TO_ODD` by more than 10%: one taken
   branch a group.
5. Past L3 the two new forms are within 15% of the scalar loop, all bound by memory. The
   round-to-odd control keeps its known gap there (78% at 256 bits, 44% at 128).
6. At SQL level the fused cast is no slower than the row path at both widths, about 1.1x end to
   end, since the kernel's nanosecond a row is small beside the query's forty. So no decline is
   built.

The default at each width is whichever form is fastest in L2 and L3 by the minimums of the two
runs, among those at least as fast as the scalar loop. If none is, the default is `SCALAR`, which
is the done-when's "converts the lanes by a scalar cast".

## 7. Risks

1. `withLane` on a float vector may not be intrinsic at AVX2 (**[I]**). Step 1's model reads
   it, and the spill form replaces it if it falls back.
2. `reduceLanes(OR)` on a two-lane long vector may fall back as the masks do (**[I]**). Step 1
   reads it. If it does, `WINDOWED` is built at four lanes only and the 128-bit default is
   `SCALAR`.
3. The branch makes a method's C2 code depend on the data. A batch alternating in and out of
   the window pays a misprediction per group. The past-window rung bounds the all-out case; a
   mixed rung is not planned.
4. The per-width default makes the defaults' bytes differ between widths for `L2F` kernels.
   `emitted_bytes.json` is written at the laptop's width, so the 128-bit form needs its own
   `PinsDefaults` test at `lanesOverride=2`.

## 8. Sequencing

Two pull requests, each under 1000 counted lines.

1. **600a** (about 450 lines). Step 1: a JDK-only model (`results/VARKA-600/`) on the laptop
   under `-XX:UseAVX=2` at `MaxVectorSize` 16 and 32, reading `-XX:+PrintIntrinsics` for
   `lane`, `withLane` and `reduceLanes`. Done when its log has no `failed to inline
   (intrinsic)` for them, or the risk 1 or 2 branch is taken and recorded here. Step 2: the
   option, both forms, the proof claims and the suite cells, default unchanged. Done when
   `build/sbt 'catalyst/testOnly *VarkaEmitterWideConversionSuite *VarkaEmitOptionSuite'` and
   `dev/varka_prove.sh --solver both round_to_odd.smt2` pass. Step 3: the benchmark arms and
   rung. Done when a laptop run at `-XX:UseAVX=2` prints every arm with its first answer checked.
2. **600b** (about 200 lines, after the owner's runner dispatch). Step 4: the runner results at
   both widths, section 6.1 scored, the default set per width. Done when
   `dev/varka_bench_diff.py --within <file> --ab "cast(l AS float), a scalar loop" "<default>"`
   shows the default no slower at every in-cache rung, by the minimums of the two runs. Where
   every vector form loses and the default is `SCALAR`, the bar is prediction 3's instead:
   within 15% of the scalar loop, which is the done-when's scalar cast. Step 5: the
   regenerated files and the SQL baseline re-run. Done when `dev/varka_quote_check.py` passes and
   the baseline's `BIGINT CAST(l AS FLOAT)` sections read within 5% of 1.0X or above at both widths.

Done when (the row's): a vector `L2F` form below level 3 runs at least as fast as the scalar loop
on the EPYC 7763 at both widths, proven in `round_to_odd.smt2` and held by the wide-conversion
suite, or the kernel converts by a scalar cast at each width where every vector form loses. A
decline to the row path happens only where a baseline section shows the fused cast slower.

## 9. Outcome

### 9.1 Part 600a, built 20 November 2026 (branch `varka-600-bigint-float-avx2`)

Steps 1 to 3 are built. Nothing is measured on the runners yet. Two checks ran on the
laptop: `build/sbt catalyst/Test/compile sql/Test/compile` passed, and so did
`build/sbt 'catalyst/testOnly *VarkaEmitterWideConversionSuite'` (9 tests, 0 failed, 18.6 s,
at master `0b8be5a33e4` plus this branch). That second run was on the AVX-512 laptop with no
`UseAVX` flag, so it checks the answers of the forms emitted at level 2, not what C2 makes of
them on AVX2.

**Step 1, the JDK-only model** (`results/VARKA-600/NarrowL2F600.java`). It runs both new forms
in plain Vector API code and checks every row against `(float) v`, inside the window and past
it. Under `-XX:UseAVX=2` at `MaxVectorSize` 16 and 32 on the laptop, C2 compiled
`VectorSupport.reductionCoerced` (the window test's `reduceLanes(OR)`), `extract` (`lane`) and
`insert` (`withLane`) as intrinsics in the forms' methods, and printed no `failed to inline
(intrinsic)` and no `not supported` line for them. The four logs are
`results/VARKA-600/intrinsics-{windowed,scalar}-{128,256}bit.txt`. So risks 1 and 2 are not
taken, and both forms are built at every width. Three caveats on the record:

* The first runs were tiered, with no `compileonly`. They did print `failed to inline
  (intrinsic)` lines, one `reductionCoerced` at 128 bits among them. Those lines came from
  compiles of other methods, interleaved in the output, and could not be attributed. Those logs
  were overwritten. The committed logs are the rerun with `-XX:-TieredCompilation` and
  `compileonly` on the model's three methods, so every line in them belongs to the forms.
* In `intrinsics-windowed-128bit.txt` no `insert` line appears. The model's slow branch there
  is the scalar lanes, and that log reads `FloatVector::withLane (0 bytes) failed to inline:
  virtual call` at both of its compiles. C2 did not resolve the receiver to `Float128Vector`
  on that branch, so the call was neither inlined nor intrinsified. So the `withLane` evidence
  at 128 bits is `intrinsics-scalar-128bit.txt` alone, where the scalar form's own loop inlines
  it as the `insert` intrinsic. The emitted `SCALAR` form calls `withLane` on the result of
  `FloatVector.zero` of a constant species, as the scalar model does, but the width audit under
  `UseAVX=2` is owed to confirm it (risk 6).
* `compileonly` leaves the rest of the model interpreted, so the ns a row the logs print is not
  a measurement and is not quoted. A tiered rerun read the thread's allocation over 1000 warm
  calls: 0.0 bytes a row for both forms at both widths. So the windowed form's join of two float
  vectors did not keep a box in the model. `skills/emitter-and-ir.md` records a join of a whole
  and a masked load form that did keep one. The emitted kernel is not the model, so the
  runner's allocation is still to be read (risk 5 below).

**Step 2, the option and both forms.** `VarkaEmitOptions.narrowConversionForm`
(`NarrowConversionForm`: `ROUND_TO_ODD`, the default, `WINDOWED`, `SCALAR`) with its
`VarkaEmitOption.TABLE` entry: a `MACHINE` choice, read by a `ConvertLane` alone, whose subject
is `useAVX=2`. The option matrix therefore gains `useAVX=2,narrowConversionForm=WINDOWED` and
`useAVX=2,narrowConversionForm=SCALAR`. `VarkaConversionLowering` gains `emitWindowed`
(the test `(v + MAGIC_WINDOW) >>> WINDOW_SHIFT`, `reduceLanes(OR)`, `lcmp`, `ifne`, then the
bounded magic and `D2F`, or the rounding to odd, which parks `v` in the same local) and
`emitScalarLanes` (the zero float vector, then per long lane `lane`, `l2f`, `withLane`). The
scratch is the rounding to odd's four locals for both of those, and one for the scalar form.
The proof claims are in `VarkaProofFiles.roundToOdd`: the test is zero exactly on the window,
the magic's double rounded once is `(float) v` there, and a test one bit wider admits a `v`
whose float the magic gets wrong (sat). The suite cells are in
`VarkaEmitterWideConversionSuite`: both forms at the cells, widths and bodies, the window's
edges, the combined kernel, the binades, the no-`L2F` check, and a new straddle test. That test
puts one outside lane at every row of a batch two groups and a remainder long, at 2, 4 and 8
lanes, so it reaches every lane index and the epilogue. `VarkaDifferentialHarnessSuite` holds
both forms to the row engine at level 2.

**Step 3, the benchmark.** `VarkaWideConversionBenchmark` gains the arms
`cast(l AS float), windowed` and `cast(l AS float), scalar lanes`. It also gains a fourth rung,
sized for L2, whose magnitudes run from `2^52` to `2^62` of either sign, so every group is outside
the window. The bounded magic `L2D` is left out of that rung, because it would decline every
batch. The laptop run at `-XX:UseAVX=2` that step 3's done-when asks for has not been made
(9.5).

### 9.2 Registered op counts, by hand and held by the budget suite

`dev/varka_emit.sh --table` was not run in this window. The counts below are the emitted lane
calls as `VarkaEmitterBudgetSuite` counts them, and that suite now asserts each one:

| form | lane calls | the plan's 3.3 said |
|---|---|---|
| `ROUND_TO_ODD`, either step variant | 24 | 24: right |
| `WINDOWED` | 31: the test 3, the magic arm 4, the round to odd 24 | about 7 plus 24: right |
| `SCALAR` | `2 * lanes + 1`: 5, 9, 17 at 2, 4, 8 lanes | about 3 a lane: wrong, 2 a lane |

The plan's `SCALAR` count was one call a lane too high. The conversion is the `l2f` bytecode,
not a lane call. Only the read and the insert are lane calls, plus the one zero vector.

### 9.3 Where the plan was wrong

* **Section 5's fuzzer bullet.** `VarkaIrFuzzSuite` does not draw the new option from its
  table entry. `VarkaEmitOption.drawnInGeneral` leaves out every option that `readOnlyBy`
  names, which a `ConvertLane`-only option is (`VarkaEmitOptionSuite` asserts this for all
  five). Only the option matrix's two new configurations run the fuzzer and the suites under
  the new forms.
* **Section 3.3's `SCALAR` count** (9.2).

### 9.4 Decisions taken while the owner was away (20 November 2026)

1. **The default stays `ROUND_TO_ODD` in 600a.** Step 2 says "default unchanged", and question
   10.1 (a per-width default) needs the runner numbers and the owner's answer. So no width
   reads the option yet. 600b sets the default.
2. **The budget weighs an `L2F` at `WINDOWED_CALLS` (31), up from 24.** This follows section
   3.3 and the `L2D` precedent: the weight is the largest form's, since `weightOf` cannot ask
   which form will be emitted. It applies at every level, the defaults' too, as 24 did. The
   weight is read in one place: `VarkaLoopEmitter`'s `GroupOps` sums it, and `admit` compares
   the sum with `groupBudget` (16), and with `fusedCeiling` (400) under reuse or the prediction.
   One `L2F` is over 16 at either weight, so a second output joins such a group only by reuse or
   the prediction. Below 400 the two weights give the same answers, and a group changes only
   where its sum crosses 400 between them: 13 to 16 casts at 31 against 24. So 3.3 conflicts
   with 3.2's "level 3 unchanged" in that one case, a kernel with a dozen or more of these
   casts in one group. The bytes-moved check after the regeneration is owed (9.5) and settles
   whether any pinned shape is one. The other choice was to leave 24 and
   under-weigh `WINDOWED`. That would have broken the budget suite's rule that the weight is
   the largest form's.
3. **The slow arm is an in-kernel branch, not a batch decline** (question 10.2). The plan
   builds the branch, and the decline would have needed a refusal that `ROUND_TO_ODD` makes
   unnecessary.
4. **The scalar form starts from a zero float vector**, not the spill through arrays that
   section 3.1 kept as a fallback. Step 1 found `withLane` intrinsic, so the fallback was not
   needed, and the zero leaves the upper half as `convertShape`'s part 0 does.
5. **The benchmark's past-window rung leaves out the bounded magic `L2D`**, which would
   decline every batch there, and keeps every other arm. That includes the `DOUBLE` controls,
   which the values do not change.

### 9.5 Owed

Also owed as risks for 600b:

* **Risk 6.** In the emitted `SCALAR` kernel, `withLane` may be left as a virtual call, as it
  was on the model's cold branch at 128 bits (9.1). The width audit and the runner rate read
  this.
* **Risk 7.** The benchmark emits a fresh kernel per rung, so no windowed kernel runs with both
  arms taken. If C2 prunes the arm it has not seen, prediction 4 is read at its best case. A
  kernel warmed inside the window can then deoptimize at its first outside group, which the
  benchmark does not measure. Reading it would take a mixed rung, which the plan leaves out
  (risk 3).


* Runs not made in this window. Each is listed with its reason in the draft PR body
  (`~/varka-ops/bodies/pr600-draft.md`): the regeneration (`emitted_bytes.json` option arms, the
  bytes-moved check after the weight change, `round_to_odd.smt2` rendered from the new
  claims), `dev/varka_prove.sh --solver both round_to_odd.smt2`, the suites listed in step 2's
  done-when, the width audit under `JDK_JAVA_OPTIONS=-XX:UseAVX=2` at both widths, the
  registry, option and matrix suites, and the option matrix's two new configurations.
* Step 3's laptop run of the benchmark under `-XX:UseAVX=2`.
* 600b in full: the runner runs at both widths, section 6.1 scored, the default per width.
* Risk 5 (new): the emitted windowed kernel's allocation. The benchmark's harness does not
  print allocation, so 600b reads it beside the runner run, by the thread allocation counter
  the model used, before a slow windowed arm is read as the form's own cost.

## 10. Questions for the owner

1. **A default that differs by width.** Recommended: yes, resolved from the emitted lane count,
   which is already in the shape key. The done-when is per width, and step 1 may leave
   `WINDOWED` only at four lanes. I cannot decide it: no default in `VarkaEmitOptions` depends
   on the width yet, and that is a design rule for the option record, which is the owner's. If
   the answer is no, the single default is the fastest form at 128 bits that also meets the bar
   at 256.
2. **The in-kernel slow branch rather than a batch decline past the window.** Recommended: the
   branch. A declined batch reruns on the row path, so its cost is the whole query's for that
   batch, while the slow branch costs only the round to odd's extra nanosecond a row over the
   fast one; end to end the difference is a few percent of that batch. I cannot decide it because it
   changes `MAGIC`'s precedent of declining batches; the plan builds the branch.

