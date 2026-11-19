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
   `VarkaConversionLowering.java` and `VarkaWideConversionBenchmark.java` lists only #1461
   (row 584, aarch64), which leaves the x86 forms alone. The default `L2F` is still the
   round-to-odd form with the logical-shift sign and `sel` (`roundToOddByShifts=false`).
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
   long casts fuse. The row path there costs about 40 ns a row, against about 1 ns for any
   kernel form here. So the decline the done-when allows is not expected to trigger, but no
   committed file shows it yet.

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
(c) Declining the cast below level 3 everywhere. The row path is about 40 times slower per row
than any kernel form (2 item 5). Kept only as the done-when's last resort, per width, if a
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
| `VarkaEmitterWideConversionSuite.scala` | each form at the cells and binades |
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
5. Past L3 every arm is within 15% of the scalar loop, all bound by memory.
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
   shows the default no slower at every in-cache rung. Step 5: the regenerated files and the
   SQL baseline re-run. Done when `dev/varka_quote_check.py` passes and the baseline's
   `BIGINT CAST(l AS FLOAT)` sections read at least 1.0X at both widths.

Done when (the row's): a vector `L2F` form below level 3 runs at least as fast as the scalar loop
on the EPYC 7763 at both widths, proven in `round_to_odd.smt2` and held by the wide-conversion
suite, or the kernel converts by a scalar cast at each width where every vector form loses. A
decline to the row path happens only where a baseline section shows the fused cast slower.

## 9. Outcome

Not built.

## 10. Questions for the owner

1. **A default that differs by width.** Recommended: yes, resolved from the emitted lane count,
   which is already in the shape key. The done-when is per width, and step 1 may leave
   `WINDOWED` only at four lanes. I cannot decide it: no default in `VarkaEmitOptions` depends
   on the width yet, and that is a design rule for the option record, which is the owner's. If
   the answer is no, the single default is the fastest form at 128 bits that also meets the bar
   at 256.
2. **The in-kernel slow branch rather than a batch decline past the window.** Recommended: the
   branch. A declined batch costs the row path's 40 ns a row, against about 1 ns for the round
   to odd. I cannot decide it because it changes `MAGIC`'s precedent of declining batches; the
   plan builds the branch.

