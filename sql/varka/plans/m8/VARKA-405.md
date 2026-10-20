# VARKA-405: the kernel warm-up's verdict under a second kernel class

*Row 405 of `m8/PLAN.md`, filed by row 398 on 19 October 2026 (`VARKA-398.md` 7) and built on
20 October 2026 at master `9f765541576`. The row's first half asked for a measurement before any
fix; the measurement came out against the premise, and this file records that and what was done
instead.*

## 1. The premise

`VarkaFusedKernel.run`'s scratch form is an interface default method that drops the scratch
address and calls the kernel's seven-argument `run`. The warm-up's calls and the evaluator's both
enter a kernel through it. In a JVM where one kernel class has reached it, C2 compiles it with the
whole kernel inlined (`VARKA-398.md` 2), and the verdict reads that code. Row 405's concern: in an
executor a second kernel class through the same method fails that compile's class check, the
compile is thrown away, and a first kernel's calls fall back to its own `run` chain, which the
warm-up made no direct calls to and which "may have no C2 code".

## 2. The measurement

`VarkaKernelWarmupProbe` takes a second argument, `others`: after the first kernel's verdict and
the readings `VarkaKernelWarmupSuite` already takes, it warms three more shapes (`LATER_SHAPES`,
one to two outputs each, a class apiece) one after the other through the same default method, then
reads the first kernel again, 16 calls of 1,024 rows per batch kind, through the scratch form
(`bytes`) and through its own seven-argument `run` (`own`), and prints its JIT state.

| run | claim | cores, busy loops | after the verdict: kernel's code | after three more shapes: bytes through the scratch form and through `run` |
| :-- | :-- | :-- | :-- | :-- |
| suite test | some | 24, 0 | not read | 1,920 and 1,920 |
| 1 | some | 2, 3 | not read | 1,920 and 1,920 |
| 2 | some | 2, 3 | not read | 5,760 and 9,600 |
| 3 | all | 2, 6 | not read | 1,920 and 1,920 |
| 4 | all | 2, 6 | not read | 1,920 and 7,680 |
| 5 | none | 2, 6 | not read | 1,920 and 1,920 |
| 6 | some | 2, 6 | not read | 0 and 1,920 |
| 7 | all | 1, 6 | not read | 1,920 and 3,840 |
| 8 | some | 1, 6 | not read | 1,920 and 1,920 |
| 9 | some | 1, 6 | four loops L4, `run`, `runDense`, `runMasked` none | not read |
| 10 | all | 1, 6 | four loops L4, drivers none | not read |
| 11 | all | 1, 6 | not read | 1,920 and 1,920 |
| 12 | some | 1, 6 | not read | 1,920 and 1,920 |

(Runs 9 and 10 printed the kernel's state right after the verdict and are the two this table does
not show after the shapes; runs 11 and 12 are their repeats that did.) Each reading after the
shapes also lists the kernel's `run` and four loops at C2 (L4, state 0).

The limit the suite applies is 16 x 256 x 4 plus the fixed allowance, over 16 KiB; an interpreted loop boxes
24,856 bytes a call at 256 bits (`VARKA-398.md` 2), 397,696 over sixteen. The most any reading shows is 9,600
bytes, a compiled kernel's segments beside an interpreted driver. In every run, after the other
shapes had warmed, the first kernel's `run`, `loopDense0/1` and `loopMasked0/1` had C2 code
(`runDense` and `runMasked` inline into `run`), and the scratch form and the own `run` both stayed
in that range. At the verdict itself the loops had C2 code and the three driver methods had none in
both runs that read it (9 and 10, one core): the compiles of the chain the default method
inlines are requested by the same calls that request the default method's, and on every run here
the loops landed before the verdict, which is the pace of the verdict's own probes (a probe is
4 x 1,024 rows and waits for every loop it reaches). The driver methods are the only part a task
could meet without code, and an interpreted driver allocates its memory segments, not vectors.

**So the premise does not reproduce.** No run showed a `COMPILED` verdict followed by a boxing
chain, with or without a second class, on one to four cores beside up to six busy loops. What
remains of row 398's runner observation is a window the laptop does not open: a standalone loop
compile still queued (or dropped by `TieredCompileTaskTimeout` after 50 ms without invocations,
`VARKA-398.md` 2) when the verdict came. A deoptimised default method would then leave that loop
interpreted until its compile is requested again and lands, which a call count does not delay
(the counters are far past the threshold). That is a transient, not the standing fault the row
describes, and no run here caught it.

## 3. What was done

* **No change to the warm-up.** The done-when's warm-up clause (wait for the kernel's own chain, or
  enter it through a call site that cannot inline it) is conditional on the first reproducing, and
  the brief for this row said to stop and report when it does not. The warm-up's time is therefore unchanged: the
  probe's first kernel ran for about three quarters of a second (744 to 789 ms) and 14,200 to 16,100 calls unstarved.
* **The test stays, as a guard.** `VarkaKernelWarmupSuite`'s "a kernel stays free of boxing through
  the evaluator's entry after other kernel classes have been warmed through the same default
  method" asserts both entries stay under the suite's limit after three more shapes. It passes on
  master (1.5 s; there is nothing to show failing), and fails if a change makes the first kernel
  fall back on a chain that boxes.
* **The override's cliff is stated, not refused** (the done-when's cliff clause). `lanesOverride` is not a session
  setting: `VarkaSessionEmit` carries `useAVX` and `exactRefusalStatus` only, and the override
  reaches a kernel through the test hook `VarkaColumnarToRowExec.setEmitOptionsForTesting` and
  through `varka.matrix.config`, which is how the option matrix reruns every suite at 2, 4, 8, 16
  and 32 lanes, sql/core's end-to-end suites included (`VarkaProjectExecSuite` cancels its
  compiled-behaviour assertions there). A refusal where it is set would remove that
  configuration's coverage of the emitter at four widths, so the cliff is documented at the option
  (`VarkaEmitOptions.lanesOverride`) and at the warm-up (`VarkaKernelWarmup`, "What it cannot
  reach"). Measured with the probe at `lanesOverride=4` under `-XX:MaxVectorSize=32` (preferred
  8 lanes) and a 15 s deadline: `RELEASED` at the deadline, the last probe 1,015,808 bytes, then
  4,063,232 bytes over sixteen calls of 1,024 rows for each batch kind (253,952 bytes a call).

## 4. Explicitly out of this row

* Making the template warm-up compile for a second species. A JVM has one preferred species and
  production never sets the override; the suites that need compiled behaviour at a width run the
  JVM at that width (`-XX:MaxVectorSize`), as `VARKA-398.md` 8 did.
* Catching the transient window of section 2. It needs a deterministic way to hold a standalone
  compile back without costing the inlining (a per-method `CompileThresholdScaling` removes the
  profile C2 inlines on, `VARKA-398.md` 2); filed here as a finding, not a row, since the standing
  fault the row describes is not there.

## 5. Outcome

Done on 20 October 2026 without a warm-up change: the first kernel's own chain was compiled and
non-boxing after a second to fourth class in each of the 10 forked runs that read it after the
shapes and in the suite test; the override's cliff is measured and documented. Calibration: the work was a measurement and a guard,
no prediction was registered.
