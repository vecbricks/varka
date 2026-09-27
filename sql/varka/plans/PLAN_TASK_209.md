# Task 209: A cliff under the byte budget - too many Vector API calls in one loop method

*Scoped 25 September 2026 (milestone 6 row 209, from task 198's first run); planned
27 September 2026, before any measurement of its own.*

## 1. What is known

**The finding** (`PLAN_TASK_198.md` 3). Sixty-four cheap tails over one date, `year(d) + k`,
fuse by default into one group, since the fused ceiling of 400 operations admits a group that
reuses its prefix. Every method of that kernel is under the byte budget - `loopDense0` is 3763
bytes, with 225 `IntVector` call sites - and it ran about sixty times slower than the same
outputs in six groups. C2's assembly of the method is 72613 instructions with no `vpmulld`,
`vpsubd` or `vpsrld`, where eleven of the same outputs compile to 4407 instructions with 196
`vpmulld`: the calendar arithmetic ran as the Vector API's scalar fallback, the failed-intrinsic
shape `PLAN_TASK_165.md` recorded, in a method with the calls inlined away and no call left to
find.

**The complication** (`PLAN_TASK_198.md` 6). The quiet run refuted the picture of one slow shape
and one fast: the speed is decided per JVM run. Over the band's five runs the one-group kernel
was fast every time, about 4 ns a row, and the six-group kernel fast in some runs and at about
250 in others, a spread of 10056% in `VarkaSharedPrefixBenchmark-jdk25-band.txt`, the widest the
project has recorded; and the committed results file, a run of its own, holds both arms slow -
257 ms for the one group and 412 for the six over a million rows. So no arm is safe by its
shape, and timing cannot say which side a run landed on.

**What the record suggests, and does not settle.** Task 46 found a kernel flip between two
regenerations that was a refused call, not register pressure; task 153's audit gives the way to
ask C2 which vector calls it refused, per shape, and which of its three answers is a verdict
(`the-jit.md`, "Ask C2 which vector calls it refused"); and the row names C2's inlining budget,
which counts nodes of a graph Varka never sees. Scope item 56 names JDK 27's
`DelayAfterInliningCutoff`, a diagnostic flag that keeps inlining past the cutoff and shipped
switched off. None of this says why the same class lands on either side from one JVM to the
next.

**Why it is in this milestone.** The post says Varka has no cliff. This is one Varka has by
default, in a shape the ladder's family produces, and the byte budget does not see it.

## 2. The admission check

The check produces the JVM's own account of a slow run beside a fast one, for the same class,
and the count of call sites at which slow runs begin. No emitter code changes before it reports.

**2.1 The probe.** `VarkaInliningCliffProbe`, a forked JVM per run in the shape of
`VarkaWidthAuditProbe` and `VarkaDeoptCycleProbe`: it emits task 198's cheap-tail shape at a
given fused ceiling (400 makes one group of 225 call sites, 100 two, 50 six), runs the kernel
over 1024-row batches for a fixed number of calls, and prints nanoseconds a row for the last
quarter of them, so that each run has a verdict - fast or slow, by a factor of ten apart. Each
run carries `-XX:+LogCompilation` for `dev/varka_c2_report.py`, and
`-XX:CompileCommand=PrintInlining` and `PrintIntrinsics` scoped to the emitted class, whose
lines say whether an intrinsic was applied and, if not, which of the three answers it was.
Twenty runs per arm, with and without `-Xbatch`, since `-Xbatch` fixes the order of
compilation and may take the variation with it - which would itself be the finding.

**2.2 What is read out of each run.** From the compile log: every C2 compilation of the loop
method, standard and OSR, in order; for each, the inlining refused inside it and its reason
(`NodeCountInliningCutoff`, `size > DesiredMethodLimit`, `too big`, `already compiled into a big
method`, or an intrinsic's `not supported`, `missing constant`, `unbox failed`); the count of
inlined bytes; and whether the method was made not entrant and recompiled.
`dev/varka_c2_report.py` learns to count intrinsic outcomes beside inline refusals. From the run
itself: the verdict, the allocation per call (a scalar fallback boxes; the warm-up's sampler
already measures this), and for one slow and one fast run the assembly through
`dev/varka_emit.sh --asm`.

**2.3 The sweep.** The same probe over call-site counts: the shape at 16, 24, 32, 40, 48, 56 and
64 outputs in one group, twenty runs each, giving the rate of slow runs per count. The cliff's
position is the largest count with no slow run in twenty, and the margin below it is the
budget's.

**2.4 Two diagnostic arms, not fixes.** Runs with C2's cutoffs raised
(`-XX:NodeCountInliningCutoff`, `-XX:LiveNodeCountInliningCutoff`, `-XX:MaxNodeLimit`, read from
`-XX:+PrintFlagsFinal` first): if a raised cutoff removes the slow mode, the mechanism is named.
And, on a JDK 27 build if one is at hand, `-XX:+DelayAfterInliningCutoff` (scope item 56).

### 2.5 Predictions, registered before the check

1. Every slow run's compile log names a budget refusal inside the loop method's last C2
   compilation, and no fast run's does.
2. The slow mode is the scalar fallback: the allocation per call is over the warm-up's
   allowance, and the assembly has no vector multiply.
3. The rate of slow runs is zero at 16 and 24 outputs, and rises with the count; at 64 it is
   above a quarter of the runs.
4. Under `-Xbatch` every run of an arm lands on the same side, which puts the variation in the
   order of compilation rather than in the code.

What the check would reject: a call-site budget, if the refusal turns out not to be a budget's
(an intrinsic `not supported`, which no count fixes); and any design at all, if the slow mode
does not reproduce under the probe.

## 3. The design, conditional on the check

**A. A call-site budget beside the byte budget.** The emitter counts the vector call sites of
each loop method as it counts its bytes, and a group whose method is over the budget is split
as one over the byte budget is: the same regrouping, one more unit. The budget's value is the
sweep's, with its margin, and it is a `VarkaEmitOptions` field with the default in
`VarkaEmitBudget`, so the bytes oracle keeps one point in the option space. The unit is Varka's
own - a count the emitter has before the class is built - which is what makes this a budget
rather than a measurement after the fact; the check has to show it tracks C2's refusal.

**B. Helper methods inside a group.** Splitting a group's body into several methods that share
its prefix would keep the sharing task 198 wanted, but a vector passed to a method C2 does not
inline is a vector on the heap, which is the failure itself. Not built unless A fails and the
check shows why helpers would inline.

**C. The ceiling alone.** Lowering the fused ceiling of 400 splits these shapes today, but it is
an operation count, the unit task 190 is retiring, and it bounds the wrong thing: a group of
few outputs with long tails has few call sites. A is the ceiling in the right unit.

The design's interaction with task 190: 190's premise was that bytes bound every method; this
task shows a second limit in C2's own accounting, so the byte budget gets a companion rather
than a successor. Task 200's exact grouping, if built, takes the call-site cost as a second
term.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/PLAN_TASK_209.md` | this plan |
| `.../codegen/varka/VarkaInliningCliffProbe.scala`, `VarkaInliningCliffSuite.scala` | the forked probe and the suite that runs and reads it |
| `dev/varka_c2_report.py` | intrinsic outcomes counted beside inline refusals |
| `sql/catalyst/benchmarks/VarkaInliningCliff-jdk25-probe.txt` | the check's readings: per-arm verdicts, refusals, the sweep |
| `.../codegen/varka/VarkaEmitBudget.java`, `VarkaEmitOptions.java`, the emitter's grouping | design A |
| `sql/catalyst/benchmarks/VarkaSharedPrefixBenchmark-jdk25-*.txt`, the ladders | regenerated |
| `sql/varka/plans/PLAN_MILESTONE_6.md` | row 209 |

## 5. Tests, and what each is for

1. **The probe's own suite** pins the check's shape: a kernel at the budget's count compiles
   with every intrinsic applied in twenty runs, and one past it is what the check found. It
   runs in the nightly beside the deopt step, since it forks JVMs and takes minutes.
2. **The budget's unit tests**: a group over the call-site budget is split, one under it is not,
   and the bytes oracle's digests move only for shapes that regroup.
3. The Varka suites of `catalyst` and `sql`.

## 6. The measurement

`VarkaSharedPrefixBenchmark` regenerated with the budget in place, and the size ladder and
`VarkaMethodSizeBenchmark` as the controls, on the quiet laptop, with the probe's twenty runs
per arm as the verdict on the cliff itself.

### 6.1 Predictions, registered before the measurement

1. No slow run in twenty, for any arm of the cheap-tail shape, at either width.
2. The steady state of the shapes that do not regroup is unchanged within their band.
3. The shapes that regroup pay the recomputation task 198 priced, and no more.

## 7. Risks

1. **The slow mode does not reproduce under the probe.** Then the run-to-run variation is in
   something the probe does not vary - the JVM's history before the kernel, which task 153's
   audit found matters - and the check widens to a JVM that has compiled other kernels first.
2. **The refusal is not a count's.** An intrinsic refused for its shape, not its neighbours,
   is a different task; the check names it and this one stops.
3. **The threshold moves with the JDK or the width.** The sweep runs at both widths on this
   JDK; the nightly probe is what catches a move on another.

## 8. Sequencing

The plan first, this pull request. Then the check, its readings committed with the plan's
section 9 scoring the predictions of 2.5. Design A only if the check admits it, measured
against 6.1, as its own pull request.
