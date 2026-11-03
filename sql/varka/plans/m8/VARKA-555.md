# VARKA-555: The both-bodies check accepts two NaNs on a floating output

## 1. Where this came from

Option-matrix run 41608460861 failed in bin b003, in `VarkaCoverageHarnessSuite`'s "Floating
arithmetic: try_add(x, x2) matches the row engine under both consumers": "dense and masked bodies
disagree on output 0 at row 7 of a null-free batch of 10 (VARKA-303)". The test JVM runs with
`-Dvarka.checkBothBodies=true` (`dev/varka_matrix.sh`), so every batch a kernel serves also runs
through its other body and `VarkaKernelRunner.compareBodies` requires the two outputs to be
byte-equal (`m7/VARKA-303.md`). Production runs without the check.

## 2. The admission check, done

Row 7 of the coverage fixture holds x = -NaN (`fff8000000000000`) and x2 = NaN
(`7ff8000000000000`). Which NaN `x + x2` or `x * x2` returns depends on who computes it: the
interpreter returns the right operand's, C1 and the Vector API's Java fallback the left's, and C2
sorts a commutative non-predicated vector op's inputs by node index
(`VectorNode::should_swap_inputs_to_help_global_value_numbering`, `AddVD` and `MulVD` among them,
HotSpot `src/hotspot/share/opto/vectornode.cpp:1088-1146` in jdk-25-ga). The dense and masked
loops are the same bytecode compiled at different moments, so one can run at a tier that picks
the other operand. A probe on the laptop with `-XX:CompileCommand=exclude,*.loopMasked0` reproduced
it: 49,598 of 50,000 calls disagreed.

Neither Spark nor Java fixes that payload, and the harness already says so when it compares
Varka with Spark (`VarkaKernelChecks.unfixedNaN`, `VarkaExpressionHarness.unfixedNaN`). So the
kernel is not wrong and nothing in it should change; the check is stricter than any contract the
answer has.

## 3. The design

`VarkaKernelRunner` gains `dstFloating`, a `boolean[]` beside `dstWidth`, set by
`VarkaEvaluatorBase` where each output's Arrow vector is allocated (`Float4Vector` or
`Float8Vector`, not Arrow's `FloatingPointVector`, which also covers the 2-byte `Float2Vector`).
A filter's selection bitmaps keep the default, false. `compareBodies` takes it after `dstWidth`
and, on a floating output, lets a row whose bytes differ agree when both bodies hold a NaN of
that width. Every other floating value, the sign of zero included, and every integral, decimal,
date-time and bit output stay byte-exact. A boolean array rather than an enum per output leaves
`dstWidth`'s markers (zero for a bitmap, `BIT_OUTPUT`) and their readers untouched.

The trade-off: the relaxation is wider than `unfixedNaN`, which accepts a NaN only where its
payload is open, so a body that returns a NaN where Spark fixes one goes unseen by this check.
The served body's answer is still compared with Spark's by the harness suites, and a NaN in the
other body is computed by the same bytecode.

## 4. Tests

`VarkaEvaluatorComponentsSuite` "the both-bodies check accepts any NaN against any NaN on a
floating output only" plants two bodies' outputs and calls `compareBodies`: two unlike NaN
doubles agree on a floating output and fail on an 8-byte integral one; `+0.0` against `-0.0`
fails; a NaN against a number fails either way round; the same for 4-byte floats.
`VarkaKernelEvaluatorSuite`'s boolean-output test pins the flag false on a non-floating output.
No test forces the masked loop to another tier over a real kernel: `VarkaCoverageHarnessSuite`
with `-XX:CompileCommand=exclude,*.loopMasked0` passed on the laptop with the relaxation switched
off as well as on, so the suite does not reproduce the tier split the probe did.

## 9. Outcome

3 November 2026, on the laptop: the new test and `VarkaKernelEvaluatorSuite` pass, and
`VarkaEvaluatorComponentsSuite`, `VarkaKernelEvaluatorSuite`, `VarkaFloatingLaneSuite` and
`VarkaCoverageHarnessSuite` pass with `-Dvarka.checkBothBodies=true` and the memory sanitizer.
Left: the option matrix.

Done when the tests above pass, `VarkaEvaluatorComponentsSuite`, `VarkaFloatingLaneSuite` and
`VarkaCoverageHarnessSuite` pass with `-Dvarka.checkBothBodies=true`, and the option matrix no
longer fails `try_add(x, x2)` on two unlike NaNs.
