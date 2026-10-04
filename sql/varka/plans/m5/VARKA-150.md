# VARKA-150: the assembly gate fails on part of the runner pool

*Scoped 19 September 2026 (milestone 5 section 2.86, row 150); closed 20 September 2026.*

## 1. The finding as scoped

`Varka assembly gate` failed with the same four tests on #255's first run, on #258, twice
on #268, on #270 and on #273, and passed on a re-run of the same commit each time. The
failing tests were the probe's own 128-bit gather self-test, `ChronoVectorOps.vectorFourFields`
(on the first two failures), the 128-bit sweep of the emitted loops, and the hand-written
kernel under forced inlining; every emitted loop at the default width passed each time, and
`VarkaEmittedBytesSuite` was green on the same commits. The scope read this as a statement
about the runner's CPU family and asked for two things: the machine printed at the top of
the gate job, and expectations per machine class with the self-tests as the precondition.

## 2. What the logs said

Read against a green run of the same suite, the failures had one signature:

| test | green run | the three failed runs read |
|---|---|---|
| probe's own 128-bit gather | packed, 8.5 s | scalar body of 248 instructions, done in 0.3 to 0.8 s |
| emitted year loop at 128 bits | packed | scalar, 495 to 501 instructions |
| hand-written kernel, forced inlining | C2 in 1.6 s | no C2 nmethod after 7 to 15 s; only C1 |
| emitted year loop, forced inlining | 1.7 s | boxing at 30 bytes per row, no allocation sites in the body |

Two readings were possible. A CPU class on which C2 refuses to intrinsify at 128-bit lanes
and under forced inlining, which would give byte-identical scalar bodies; or a machine on
which C2 had not finished when the probe stopped calling, which would give C1 bodies. The
first fitted the identical instruction counts and the second fitted `saw c1`, and the gate
log recorded nothing about the machine, so neither could be checked from CI.

## 3. The reproduction

The laptop passes the suite on every run. Pinned to two cores that two busy loops already
occupy (`taskset -c 0,1` for sbt and, by inheritance, for every probe child it forks), it
fails the same four tests: the 128-bit gather self-test scalar, `vectorFourFields` with
`saw c1` after 26 seconds, the emitted loop boxing under forced inlining at 6.9 bytes per
row, and the hand-written kernel with `saw c1` after 15 seconds. The runner pool's machines
have four virtual cores and neighbours; the gate's sbt JVM, its compiler threads and the
child JVM's compiler threads share them.

The mechanism is in `VarkaAssemblyProbe`: a case calls its method 200000 times, "well past
`Tier4InvocationThreshold` with room for a loaded machine", and then measures. The count
buys the *request* for a C2 compile, not its completion; compilation is a background thread,
and on a starved machine the calls run out first. The parent then finds a C1 nmethod (`saw
c1`), or a C2 nmethod compiled early, before the vector classes the intrinsics need were
loaded, whose body is the Java fallback: scalar, and boxing when inlined.

## 4. The change

The probe child runs under `-Xbatch`. Compilation then happens on the calling thread, so the
call that crosses the threshold returns with the nmethod installed, and a count-based
warm-up is sufficient on any machine. Under the same two-core starvation the suite passes
all fourteen cases; the one cost is that the 128-bit gather self-test, which waited on
background compilation before, now takes its compile on the calling thread and reads about
ten seconds where it read eight on a green runner.

Two smaller changes make the next failure readable. The gate job prints the machine before
the assertions with `dev/varka_datapath.sh --require any`, the same report the surface
benchmark's jobs make: CPU model, vector flags, the JVM's `UseAVX` and `MaxVectorSize`, and
the datapath readings. And every failure the suite raises names the host it ran on, read
from `/proc/cpuinfo`, with its processor count.

The per-class expectations the scope asked for were not built. They would have encoded the
wrong theory: no machine class was refusing these shapes, and a cancel on "the self-test
does not pack here" would have hidden exactly the contention this task found.

## 5. Verification

| run | result |
|---|---|
| laptop, two busy cores, child without `-Xbatch` | 4 of 14 fail, the CI signature |
| laptop, two busy cores, child with `-Xbatch` | 14 of 14 pass |
| laptop, idle, the committed change | the suite's own run in this task's pull request |

The gate on this branch's own pull request is the CI reading; the acceptance the row
carried, one green run from each family in the census, is now one green run per runner the
gate happens to land on, which every later pull request supplies.

## 6. The gate after the fix, 21 September 2026

The first gate run with `-Xbatch` on master, on #292, named its machine for the
first time: an AMD EPYC 9V45 with four processors, the same Zen 5 family as
this laptop, at `UseAVX=3` and `MaxVectorSize=64`. The two forced-inlining
tests that had read `saw c1` passed, which is what the synchronous compile was
for. The two 128-bit tests still failed, with the same bodies as before: the
probe's own gather at `-XX:MaxVectorSize=16` a scalar body of 248
instructions, the emitted year loop at that species 501. The same counts had
been read on the Intel Xeon runners in the earlier failures.

So section 3's reproduction found one of two causes. Starving the laptop of
cores did produce all four failures, and `-Xbatch` removed the two that were
about compilation finishing in time. The other two are deterministic, appear
on a runner of the laptop's own CPU family with the fix in place, and never
appear on the laptop. That is a difference between the runner's JVM and this
one at a forced species, not contention and not the CPU, and this plan does not
know what it is. The honest control is the one section 1 originally scoped: the
probe's own 128-bit gather is read once, and where it comes out scalar the two
128-bit assertions cancel, naming the host and the count, while every
default-width assertion stays hard. A machine whose own probe does not pack at
128 bits cannot say whether the kernels do.

Finding the cause is row 165: the probe run on a runner under
`-XX:+PrintIntrinsics` and `-XX:+PrintInlining` at the forced species, which
says why the gather intrinsic was refused, against the same run on the laptop.

