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

## 9. The admission check, done, 27 September 2026

The probe, the runner and the reader of section 2 were built and the census run the same
evening on the laptop; the readings are `VarkaInliningCliff-jdk25-probe.txt`, and every arm's
session log, per-fork compile logs and summary are under `target/varka-inlining-cliff/`. What
the census found is not what section 1 expected, and 9.1 to 9.4 say how.

### 9.1 The sweep: three cliffs, all past C1's limit

Twenty forks per case, the cheap tails in one loop method, six seconds a fork:

| outputs | loop bytes, sites | slow / fast | C1 | C2, in the fast forks | in the slow forks |
|---:|---:|---:|---|---|---|
| 16 | 1 group, 81 sites | 0 / 20 | compiles | once, settled at second 1, 0.8 to 1.0 ns a row | - |
| 24 | | 6 / 14 | fails | once, second 2, 1.2 to 1.4 | recompiled 3 to 9 times, `profile_predicate` traps, 115 |
| 32 | | 7 / 13 | fails | once, second 3, 1.6 to 1.9 | the same, 141 to 147 |
| 40 | 2491 bytes, 153 sites | 5 / 15 | fails | once, second 4, 4.6 to 7.1 | the same, 167 |
| 48 | 2915, 177 | 20 / 0 | fails | - | once, still 189 to 199 at second 6 |
| 56 | | 20 / 0 | fails | - | once in 7, failed in 12 ("out of nodes during split"), none in 1 |
| 64 | 3763, 225 | 20 / 0 | fails | - | once in 15, none in 5; 238 to 261 |

* **Past C1's limit the loop runs interpreted until C2 lands.** C1 refuses the loop method
  from 24 outputs on - "out of virtual registers in LIR generator", the limit row 170 and
  `the-jit.md` record at about 1900 bytes for the `make_date` shape; here it falls between 81
  and 105 vector call sites, at under 1500 bytes. From then on the first code the loop runs
  under C2 is what the interpreter's profile makes of it, and it arrives at second 2 for 24
  outputs, 3 for 32, 4 for 40: a second later for every eight outputs.
* **At 24 to 40 outputs the slow forks are task 189's deoptimization cycle**, a quarter to a
  third of them: C2 compiles the loop, its code traps at `profile_predicate`, is made not
  entrant and compiled again, three to nine times in six seconds, and between compiles the
  interpreter runs the loop and boxes every vector - 724 KB a 1024-row call against 8 KB in
  the fast forks. The compiled code is the same in both verdicts, to the intrinsic: 46 vector
  operations, 1616 calls left, 26 allocations eliminated at 24 outputs, in slow and fast alike.
  Row 218 says the default form never cycles; this shape, emitted with the default options,
  does.
* **From 48 outputs the six-second window is too short, not the kernel too slow.** Every fork
  was slow at second 6, with one C2 compile and no cycle at 48, and the allocation of 824 KB a
  call is the interpreter's. A fork run for eight seconds under JFR (9.3) ran at 71 ns a row
  in its sixth second and 2.7 in its seventh, with the fast side's 10 KB a call: the compile
  came at second 7, in the shape of the progression above. 9.4 measures the wide shapes with
  the window they need.
* **At 56 outputs C2 fails outright in twelve forks of twenty**, "out of nodes during split",
  and at 64 never compiles the loop in five; the two-group split (ceiling 100, about 112 sites
  a method) fails in eleven and never compiles in nine. With C2's node limits raised ten-fold
  (`MaxNodeLimit` 800000, `LiveNodeCountInliningCutoff` 400000) the failures become compiles
  that do not finish in six seconds: the graph is the size the limits say.
* **Six groups (ceiling 50, about 37 sites a method) are fast in twenty forks of twenty**, at
  4.1 to 4.4 ns a row, C1 compiling every method.

### 9.2 The predictions of 2.5, scored

1. **Refuted as worded.** No budget refusal separates the verdicts: at 24 to 40 outputs the
   slow forks' C2 compiles refuse exactly what the fast forks' do. What separates them is the
   count of C2 compiles and the traps between them. With `-XX:-ClipInlining`, which drops the
   `DesiredMethodLimit` check, every refusal moves to `NodeCountInliningCutoff` and not one
   verdict moves.
2. **Held for the mechanism, wrong about the place.** The slow mode is the Vector API's Java
   fallback boxing every vector - `int[]` and `Int512Vector` from `broadcastBits`,
   `lanewiseTemplate` and `rvOp` in the JFR profile - but it is the interpreter running the
   loop, between C1's refusal and C2's compile or between the cycle's recompiles, not a C2
   compile that left the intrinsics out. Every completed C2 compile of the loop applied its
   vector operations.
3. **Half held.** No slow fork at 16 outputs, but six of twenty at 24, where the prediction
   said none; the rate then does not rise with the count but stays near a third, and from 48
   the six-second window cut every fork off before its compile.
4. **Held.** Under `-Xbatch` all twenty forks landed on the same side - the slow one, at 175
   to 191 microseconds a row: compilation on the calling thread means every recompile of the
   cycle stops the loop for the second a compile of this method takes.

### 9.3 The JVM's account, from JFR

One fork per case for eight seconds under `jdk.ObjectAllocationSample`. At 16 outputs, fast
from the first second, 88% of the allocated weight is `NativeMemorySegmentImpl` from
`VarkaVectorSupport.ofAddress`, the segment each body constructs per input per call, at 2480
bytes a call. At 48, which settled at second 7, 60% is `int[]` and 11% `Int512Vector` from
the Vector API's `broadcastBits`, `lanewiseTemplate` and `rvOp`: the interpreter's boxing
over the first six seconds. So the steady state past the compile allocates only the segments,
and the cliff is the time before it.

### 9.4 The window the wide shapes need, 24 seconds a fork

Ten forks per case, the boundary cases again with 24-second forks:

| outputs, groups | slow / fast | the fast forks | the slow forks |
|---|---:|---|---|
| 24, one | 3 / 7 | settled at second 2, 1.3 to 1.4 ns a row | the cycle, never settled: 114 `profile_predicate` traps over three forks |
| 40, one | 3 / 7 | second 4, 2.2 to 2.3 | the cycle, never settled |
| 48, one | 4 / 6 | second 6, 2.5 to 2.9 | the cycle: C2 seven times, plus an OSR compile, never settled |
| 64, one | 10 / 0 | - | C2 failed "out of nodes during split" in five; compiled twice and trapped in five; none settled in 23 s |
| 64, two (ceiling 100) | 10 / 0 | - | C2 failed in ten of ten |

So the cliff has a shape, and it is not one number:

1. **Up to about 81 vector call sites** (16 outputs here) C1 compiles the loop, C2 follows within
   the first second, and every fork is fast.
2. **From about 105 to 177 sites** (24 to 48 outputs) C1 refuses the loop, the batches run
   interpreted for 2 to 6 seconds - a second more for every eight outputs - and then the kernel
   is fast in about two forks of three and in the deoptimization cycle for good in the third.
3. **At 225 sites in one method, or 112 in each of two**, C2 fails the compile or cycles, and no
   fork is fast in 23 seconds.
4. **Six methods of about 37 sites** are fast in every fork, at 4.1 to 4.4 ns a row against the
   1.3 of a wide method that lands well.

### 9.5 What the check admits

The design of section 3 is admitted in its unit and corrected in its value. The unit is the
vector call sites of a loop method: C1's refusal falls between 81 and 105 of them at under
1500 bytes here, where the `make_date` shape's fell at about 1900 bytes (row 170), so a limit
in bytes would have to be set for the worst shape while a limit in sites follows what C1
counts, its virtual registers after inlining the Vector API's bodies. The value is C1's limit,
not the cycle's: a method C1 compiles is fast from the first second in every fork, and a method
it refuses pays seconds of interpretation in the best case and the cycle in a third of JVMs,
whatever C2 makes of it afterwards. That answers row 170's question - whether a limit below
8000 earns its extra calls - with a number: at one group the cheap tails run at 1.3 ns a row
two times in three and 120 the third, about 41 in expectation, against 4.2 every time in six
groups, ten times better in expectation and with no seconds interpreted first.

What is still to measure before the budget is set, in its own pull request:

* **The same census on the runner classes.** C1's limit is a count of its own virtual
  registers and C2's node limit a flag, so the boundary should be the JDK's and not the
  laptop's; the cycle's incidence and the seconds before C2 are the machine's, and the
  cold-start run of the same day took 2.3 times longer to its verdicts on a four-core runner.
  One dispatch per runner class, through the probe's suite under an environment variable as
  the width audit runs, says which of the numbers above travel.
* **The boundary**, between 81 and 105 sites for this shape, at 18, 20 and 22 outputs; and
  whether it moves for the `make_date` shape, whose sites carry more registers each.
* **The ladders under the budget**: `VarkaSharedPrefixBenchmark`, the size ladder and
  `VarkaMethodSizeBenchmark` regenerated, since smaller groups recompute the prefix task 198
  priced, and `emitted_bytes.json` moves for every shape that regroups. The prediction to
  register: no fork of any ladder shape slow in twenty, and a steady state within the band for
  shapes under the budget already.
* **The warm-up path.** The `c1off` arm, the production condition, had C2 never compile the
  64-output loop within six seconds in 14 forks of 20; under the budget every loop method is
  one C1 would have compiled, which is what the warm-up's C1 exclusion gives up. Whether the
  exclusion still pays for methods under the budget is a question for the same measurement.
* **The nightly guard** takes the cheap-tail shape at 24 and 40 outputs beside the `make_date`
  ladder, since row 218's "the default form never cycles" is now false for it, with
  `dev/varka_inlining_cliff.py` given a `--fail-if-slow` for the purpose.
