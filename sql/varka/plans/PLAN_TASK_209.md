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

## 10. The night of 27 to 28 September: the boundary, the `make_date` shape and the arms

The laptop's share of 9.5's list ran unattended after #490 merged - the boundary, the
`make_date` shape, the warm-up's C1 exclusion, and two arms of the "steer the JIT" kind: a
compiler directive on the emitted class that forces `VarkaVectorSupport`'s helpers inline and
raises its `MaxNodeLimit` to 240000, and `-XX:-UseProfiledLoopPredicate`, the flag behind the
cycle's trap reason. Same JVM, machine and reader as section 9; the readings are section 4 of
`VarkaInliningCliff-jdk25-probe.txt`. The runner classes' census is still to do.

### 10.1 The boundary: C1 compiles 93 vector call sites and refuses 99

Twenty forks of twelve seconds a case, the cheap tails in one loop method:

| outputs | loop bytes, sites | C1 | slow / fast | the fast forks | the slow forks |
|---:|---:|---|---:|---|---|
| 18 | 1325, 87 | compiles | 0 / 20 | settled at second 1, 0.9 to 1.1 ns a row | - |
| 20 | 1431, 93 | compiles | 0 / 20 | second 1 or 2, 1.0 to 1.1 | - |
| 22 | 1537, 99 | fails | 3 / 17 | second 2, 1.1 to 1.2 | the cycle, 109.2 to 109.6, 75 `profile_predicate` traps over the three |

The limit 9.1 put between 81 and 105 sites lies between 93 and 99, and the two shapes give the
count's arithmetic: a cheap tail costs 3 sites on a shared prefix of 33 (69 at 12 outputs, 87
at 18, 93 at 20, 99 at 22, 105 at 24, 153 at 40, 177 at 48), a `make_date` output 55 on a
prefix of 38 (93 for one, 148 for two, 203 for three, 258 for four, 313 for five). So the
budget's value on this JDK is 93 sites a loop method - 20 cheap tails, or one `make_date`
output - and since C1's bailout is a count of its own virtual registers, the value should be
the JDK's and not the machine's, which the runner census can confirm.

### 10.2 The `make_date` shape: every method past C1 from eight outputs, and no fork slow

Twenty forks of sixteen seconds a case, the deopt guard's `make_date(year(d), month(d), k)`
outputs under the default options with the fused ceiling at 400 - which the byte budget still
splits, five outputs a loop method at 313 sites and a remainder method:

| outputs | loop methods, sites | slow / fast | settled at, ns a row | C2 on the first method |
|---:|---|---:|---|---|
| 8 | 313, 203 | 0 / 20 | second 2, 3.2 to 3.7 | once, in all 20 |
| 10 | 313, 313 | 0 / 20 | 2 to 4, 4.4 | recompiled in 12, 48 `profile_predicate` traps between |
| 12 | 313, 313, 148 | 0 / 20 | 2 to 3, 5.7 to 5.8 | once, in all 20 |
| 14 | 313, 313, 258 | 0 / 20 | 3 to 4, 5.9 to 6.4 | recompiled in 18, 72 traps |
| 16 | 313, 313, 313, 93 | 0 / 20 | 3, 6.7 to 7.8 | once, in all 20 |

* **C1 refuses every method of this shape from the first case**, since one output with its
  prefix is already 93 sites; the first code the loop runs under C2 is the interpreter's at
  seconds 2 to 4, as for the cheap tails past 20 outputs.
* **C2's first attempt at each loop bails out and its retry lands in the same compile task**:
  "retry without subsuming loads" in all 100 forks, the `<task_done>` record marked a success.
  The readers counted such a task as failed until this night; `dev/varka_c2_report.py` now
  reads the task's outcome from `<task_done>` and notes the retry, and the reading is "C2
  compiled once, after a retry".
* **The traps do not become the cycle here.** At 10 and 14 outputs most forks recompiled the
  first loop method, four `profile_predicate` traps a fork, and every one of them settled fast
  by second 4; at 8, 12 and 16 outputs the byte-identical first method (2956 bytes, 313 sites,
  the same five outputs) trapped in no fork. The cheap shape's methods of 99 to 177 sites
  cycled for good in 3 forks of 20 to 6 of 10 the same night. The cycle is not a function of
  the site count, and what C2 traps on is not only the method's own code.

### 10.3 The arms at 24, 40 and 48 outputs: the flag ends the cycle, nothing shortens the wait

Ten forks of 24 seconds a case, the cheap tails in one loop method (105, 153 and 177 sites),
slow / fast:

| arm | 24 | 40 | 48 | the fast forks settle at | C2's code |
|---|---:|---:|---:|---|---|
| C1 on, the default (the control) | 1 / 9 | 3 / 7 | 6 / 4 | seconds 2, 4, 6 | 46, 62, 70 vector ops; 1616, 2170, 2441 calls left |
| C1 on, `-XX:-UseProfiledLoopPredicate` | 0 / 10 | 0 / 10 | 0 / 10 | 2, 4, 6 | the same counts |
| C1 excluded (the warm-up's directive, task 212) | 2 / 8 | 0 / 10 | 0 / 10 | 2, 4, 6 | the same |
| C1 excluded and the `inline` directive | 0 / 10 | 2 / 8 | 3 / 7 | 2, 4, 6 | the same |
| C1 on and the `inline` directive | 1 / 9 | 2 / 8 | 3 / 7 | 2, 4, 6 | the same |

* **`-XX:-UseProfiledLoopPredicate` removed the cycle: no fork of 30 against 10 of 30 in the
  control**, with the same compiled code to the intrinsic and the same settle seconds. The
  trap reason named the mechanism and the flag confirms it: C2 hoists a predicate out of the
  loop on the profile's word, the predicate fails, the code traps and is thrown away, and the
  next compile hoists it again. It is a product flag, so it can be set on a Spark JVM today;
  what it costs everything else is the next measurement (10.4).
* **Excluding C1 reduced the cycle and did not remove it**: 2 slow forks of 30 without the
  directive, 5 of 30 with it, against 10 of 30 with C1 on.
* **The `inline` directive changed nothing C2 emitted.** It matched - the 60 forks under it
  are the 60 compile logs with a "force inline by CompileCommand" record - and the vector
  operations, the calls left and the refusals a fork are the same as the control's: the helpers
  were inlined without it, and the refusals that remain are inside the Vector API's bodies.
  Whether its `MaxNodeLimit` moved the cycle's count (5 of 30 against 2 of 30) is within these
  samples' noise.
* **The seconds before C2 moved in no arm**: 2 at 24 outputs, 4 at 40, 6 at 48, in all 150
  forks. The cliff's cost is the interpreter's time, and only keeping the method under C1's
  limit removes it - which is the budget's case, unchanged.

### 10.4 What the night settles, and what it leaves

* **The budget's value**: 93 sites a loop method on JDK 25.0.4.1 - the last count C1
  compiled, 6 under the first it refused. To confirm on the runner classes' JDK before it is
  hard-coded, and to key by the JDK version in the census that guards it.
* **The budget's price for heavy shapes**: one `make_date` output a method, its prefix
  recomputed in each, against the two or three seconds of interpretation and the traps that
  resolved that the 313-site methods pay today. The ladders under the budget (9.5) have to be
  run for both shapes, and the decision may be a budget that heavy shapes exceed on purpose:
  a method past C1 that settles in 3 seconds and never cycles is a different case from one
  that cycles for good.
* **The cycle has a switch.** `-XX:-UseProfiledLoopPredicate` is the first arm to end it, and
  its cost is unmeasured: the next night runs the throughput and parity files under the flag
  against their bands, and the deopt guard (task 189) under it. If the cost is nothing
  measurable, the flag is a line in Varka's recommended JVM options and the guard's remedy;
  the budget still owns the seconds before C2.
* **The warm-up's C1 exclusion** (task 212) keeps its case on these numbers - fewer cycles,
  never more - and gives up nothing for methods under the budget, which C1 would compile in
  the first second anyway; that trade is the ladder measurement's to price.
* **Still to do from 9.5**: the runner classes' census; the ladders under the budget; the
  nightly guard's `--fail-if-slow`.

## 11. The budget built, 29 September 2026

Design A, built in the emitter with the value section 10 read, and corrected in one respect the
suites found before anything was timed: the budget is a rule for wide groups, and a narrow group
of heavy outputs keeps its methods past C1 on purpose, the case 10.4 anticipated.

### 11.1 The unit is read off the built class, beside the bytes

`VarkaEmittedClass.measure` counts, for every method with code, the invocations of the Vector
API's vector classes - `IntVector`, `LongVector`, `DoubleVector`, and `Vector` itself, on which
`convertShape` is declared - and not the masks, species or operator tokens. That is the count
the census reported as `IntVector` call sites: the cheap tails invoke nothing else, and the
`make_date` shape's ten to twelve `VectorMask` calls an output do not change any grouping. The
count is taken from the class the byte budget already measures, not estimated from the grouping
weights, for two reasons. The weights over-count the cheap tails by two - a `year(d) + k` tail
weighs a field's seven plus one where it emits three sites - and the boundary is six sites wide,
so a weight-based budget would have split at fourteen tails what C1 compiles at twenty. And the
regroup was already there: a group whose loop method measures over the budget is given a forced
start at its middle output and the class is built again, exactly as for a method over its bytes
(`VarkaLoopEmitter.emit`). The budget reads loop methods only; an epilogue runs once a batch and
reaches C2 by invocation count, so C1's refusal of it costs a little per batch and no cycle.

The value is `VarkaEmitBudget.LOOP_CALL_SITE_BUDGET`, 93, C1's last compiled count on JDK
25.0.4.1 (10.1); the option is `VarkaEmitOptions.loopCallSiteBudget`, 0 for off, rendered in
the canonical string only when it differs from the default, so no committed hash moves. Under the
legacy form, `methodByteBudget` 0, nothing is measured and the budget does not apply, so that
form stays the reference it is. The budget never declines: a group it cannot bring under runs
under C2 in seconds, which is far better than the row fallback that a decline would mean, where
the byte budget declines because a method over `HugeMethodLimit` is never compiled at all.

### 11.2 The blanket budget's cost, found by the suites

With the budget applied to every group over it, three existing tests failed before any ladder
was run, and each is a shape the ladders have:

* **The `make_date` ladder of sixteen** split from four groups to sixteen: one output with its
  prefix is 93 sites, so no two share a method under the budget. The shared-prefix results of
  task 198 already price that grouping - sixty `make_date` outputs in sixty methods ran at 39.1
  ns a row against 17.0 in eleven (`VarkaSharedPrefixBenchmark-jdk25-results.txt`), 2.3 times
  slower, at steady state and for good.
* **A hundred `greatest(add_months(d, k), date_add(d, k), last_day(d))` entries** - the size
  ladder's own entry - declined: each entry is about 150 sites alone, so every group split to
  one entry a method, a hundred loop methods, and the driver that calls them grew past 8000
  bytes. The blanket budget would have undone task 190's hundred-entry kernel, and its
  single-entry methods would still have been past C1.
* **Task 219's refused group** of four nested `make_date` trees regrouped to four methods where
  the class-file cap needs two.

The common shape is a group of few heavy outputs. Splitting such a group buys at most a shorter
wait for C2 - often not even that, since a heavy output alone is past C1 - and costs a call, a
loop and the prefix's loads per method per batch at steady state. The census's own numbers say
the wait is bounded and the cycle does not visit these groups: five `make_date` outputs a method,
313 sites, settled by second 4 and cycled in no fork of 116 (10.2), where the cheap tails past C1
- 99 to 177 sites, twenty-two to forty-eight outputs and as many output segments live in the
loop - cycled in a sixth to a half (10.1, 10.3). Task 198's diagnosis of the flip's cycle ties
the difference to the memory segments a loop keeps live (`PLAN_TASK_198.md` 12), which is one
per output plus the inputs and the scratch: seven for a `make_date` group of five, over twenty
for the cheap tails.

### 11.3 The heavy-group exemption

So the budget splits a group only while it holds more outputs than
`VarkaEmitBudget.HEAVY_GROUP_OUTPUTS`, six, the most the fused ceiling packs of the heaviest
calendar nodes - the sixty `make_date` outputs group as eleven under materialization, the size
ladder's hundred entries as twenty-five groups of four, at 378 to 408 sites a loop method
(`dev/varka_emit.sh`, the default against `loopCallSiteBudget=0`: the same twenty-five methods,
byte for byte) - so no group the ladders emit today is touched, and the wide cheap groups are. The option is `VarkaEmitOptions.heavyGroupOutputs`, 0 to split every group over
the budget, which is the blanket arm kept measurable. A group over the budget at six outputs or
fewer stands; the regroup halves a wider group at its middle output until each half is under the
budget or narrow enough.

What the rule does to the shapes in hand, read from the emitted classes by
`VarkaEmitterBudgetSuite`:

| shape | budget off | default | every group split |
|---|---|---|---|
| 20 cheap tails | one method, 93 sites | the same | the same |
| 22 cheap tails | one method, 99 | two: 71 and 42 | the same two |
| 48 cheap tails | one method, 177 | three, all under 93 | the same three |
| 1 `make_date` | 93 | 93 | 93 |
| 2 `make_date` | one method, 148 | the same | two: 99 and 68 |
| the `make_date` ladder of 12 | groups of four and five | byte-identical | one a method |
| `greatest` over four `add_months` | one method, past the budget | the same | the same |

The split's arithmetic under task 198's materialization: the first group computes the prefix
and stores its six vectors for the groups after it, six sites more than the prefix alone, and a
later group loads them in place of the decomposition's thirty-three. Twenty-two tails halve to
71 and 42; forty-eight to 24 and 24, of which the first, at 111 with its stores, halves again
and the second loads the prefix and fits at 78. Split by force, the `make_date` pair's producer
is 99 sites - over the budget by exactly the six stores - and its consumer 68. Whether C1
compiles a 99-site producer is a question for the probe (section 12); under the default the pair
is a heavy group and never splits, so the answer decides nothing about the shipped grouping.

### 11.4 What changed besides the emitter

* `VarkaEmittedClass` has the fourth measure, `vectorCallSites`, and `VarkaEmitBudget` the
  readers over it: `groupsOverCallSites`, which the regroup uses, and `overCallSiteBudget`, which
  `dev/varka_emit.sh` prints after the `HugeMethodLimit` line - a loop method over the budget in
  a dump is a heavy group, or the budget is off.
* `VarkaInliningCliffProbe` takes the budget and the exemption as its last two arguments and
  prints them in its `BEGIN` line, prints its methods' sites in the emitter's unit, and
  `dev/varka_inlining_cliff.sh` has `--budgets` and `--heavy` for the arms,
  `dev/varka_inlining_cliff.py` the labels. The default fork is now the production emitter;
  `--budgets 0` is the census's arm. The reader's `--fail-if-slow` and the script's switch of
  the same name make the exit status the verdict, and `dev/varka_nightly.sh` runs the cheap
  shape at 24 and 40 outputs under it, ten forks of twelve seconds, as its `cliff` step beside
  the deopt guard - the nightly guard 9.5 asked for.
* `VarkaSharedPrefixBenchmark` has two more arms per shape at the default ceiling: the budget
  off, which is the one loop method past C1 the census measured, and every group split, which
  is the blanket budget's grouping - sixty methods for the sixty `make_date` outputs.
* The tests of section 5.2: the split and the exemption on the shapes above with their counts
  pinned; the answers of the split kernel checked; the corpus property that a shape without a
  wide loop method over the budget emits byte for byte the same with the budget on and off,
  and one with such a method takes more groups and leaves no wide group over it.

### 11.5 The corpus and the bytes oracle

The property test over the first four hundred shapes of the fuzzer's sequence found no shape
with a wide loop method over the budget: 0 of 400 regroup, and 152 have a loop method over the
budget that is a heavy group - a single root of many nodes, which is the shape the grammar
draws, and which the budget leaves. `emitted_bytes.json` regenerated accordingly moves nothing:
0 of 92 coverage rows at either width, 0 of 100 fuzz blocks at either lane. The budget's default
changes no committed emission; what it changes is the wide cheap group the ladders build by hand,
which no coverage row has because no single expression is one.

## 12. The measurement, 29 September 2026

Section 6's runs, on the laptop the same afternoon the budget was built, the machine otherwise
idle (load 1.4 to 2.5 at the starts, the probe's forks and one sbt session the whole of it).
The probe's readings are section 5 of `VarkaInliningCliff-jdk25-probe.txt`.

### 12.1 The cliff, under the budget: no slow fork in eighty

The census's counts, forked again with the production emitter - the budget at 93 and the
heavy-group exemption at six, `dev/varka_inlining_cliff.sh --outputs 22,24,40,48 --forks 20
--seconds 12` - against the census's own control (10.1 and 10.3, the same counts with the
budget not yet built):

| outputs | loop methods under the budget (sites) | slow / fast, budget | slow / fast, control | settled at, ns a row |
|---:|---|---:|---:|---|
| 22 | 2 (71, 42) | 0 / 20 | 3 / 17 | second 1 or 2, 1.1 to 1.2 |
| 24 | 2 (74, 45) | 0 / 20 | 1 / 9 | second 1, 1.2 to 1.4 |
| 40 | 3 (68, 39, 69) | 0 / 20 | 3 / 7 | second 2, 1.9 to 2.2 |
| 48 | 3 (74, 45, 81) | 0 / 20 | 6 / 4 | second 2, 2.5 to 2.7 |

C1 compiled every loop method in every fork ("C1 ok x20" for `loopDense0` in each case), C2
compiled each once, no fork trapped, and the fast forks' rates are the control's fast forks'
rates: 48 outputs in three methods run at 2.5 to 2.7 ns a row where the one method that landed
well ran at 2.68 (section 3 of the probe file), and the six-group arm of the same shape ran at
4.1 to 4.4. The split costs the shape nothing measurable at steady state and removes the two
outcomes that were the cliff - the seconds interpreted and the cycle - in eighty forks of
eighty. Prediction 1 of 6.1 holds at the host's width; the 128-bit arm is 12.2.

**The make_date pair split by force** (`--shapes makedate --outputs 2 --heavy 0`, five forks of
eight seconds): the producer at 99 sites - 93 plus the six prefix stores - is compiled by C1 in
all five forks, C2 compiles it once, and the fork settles at second 1 at 1.9 ns a row. So C1's
limit for this shape is at least 99, above the cheap tails' 93 to 99, and the six stores do not
tip a method over it; the budget's value stays the cheap shape's last compiled count, which is
the conservative side. Under the default the pair is a heavy group and never splits, so this
settles nothing about the shipped grouping - it says that where the exemption is off, the
producer is not the method the split leaves past C1.

### 12.2 The same at 128 bits

`--outputs 22,48 --widths 16 --forks 10 --seconds 12`, the same classes under
`-XX:MaxVectorSize=16`: 0 of 20 forks slow, C1 compiling every loop method in every fork, C2
once; 22 outputs settle at second 1 at 2.3 to 2.4 ns a row and 48 at second 3 at 4.6 to 5.0,
about twice the 512-bit rates as four lanes against sixteen should give at these small bodies.
Prediction 1 holds at both widths, over a hundred forks in all.

### 12.3 The shared-prefix ladder under the budget

`dev/varka_bench_regen.sh catalyst VarkaSharedPrefixBenchmark`, both widths, pinned, load 0.93
at the start, the canary within 1.4% on all three legs. The default arms now carry the budget,
and two arms per shape were added at the default ceiling: the budget off, and every group split
(11.4). Per row, in nanoseconds:

| arm | 256 bits, before | 256 bits, now | 128 bits, before | 128 bits, now |
|---|---:|---:|---:|---:|
| 64 cheap tails, ceiling 400, materialized: 1 group before, 4 now | 243.2 | 3.3 | 977.2 | **265.8** |
| the same, recomputed | 264.7 | 3.4 | 967.5 | 7.5 |
| 64 cheap tails, ceiling 200 and 100, materialized: 1 to 2 groups before, 4 now | 253.6 to 258.6 | 3.3 | 927.0 to 997.7 | 5.9 |
| 64 cheap tails, ceiling 50, materialized: 3 groups before, 4 now | 129.4 | 3.3 | 518.8 | 5.9 |
| 64 cheap tails, ceiling 50, recomputed: 6 groups, both | 4.1 | 4.1 | 10.0 | 9.9 |
| 64 cheap tails, budget off: 1 group | - | 261.2 | - | 905.9 |
| 64 cheap tails, every group split: 4 groups | - | 3.4 | - | 5.9 |
| 60 make_date, default: 11 groups, both | 17.0 | 17.0 | 46.6 | 48.5 |
| 60 make_date, budget off: 11 groups | - | 17.1 | - | 49.4 |
| 60 make_date, every group split: 60 groups | - | 39.0 | - | 113.2 |
| 60 make_date, ceiling 100 and 50: 60 groups, both | 38.8 to 39.1 | 38.8 to 39.7 | 113.1 to 113.2 | 113.2 |

* **The cheap shape is off the cliff at both widths, in eleven arms of twelve.** Every
  four-group arm at 256 bits reads 3.3 to 3.4 ns a row, where the one-group arm read 243 to 265
  in this file's committed run and about 4 in the band's runs that landed well
  (`PLAN_TASK_198.md` 6): the split costs nothing at steady state and removes the outcome that
  was seventy times slower. At 128 bits the same arms read 5.9 to 7.5 against 894 to 998 before,
  and the budget-off arm, the census's one method, reads 261 and 906 in the same JVMs. Prediction
  3 of 6.1 holds: the regrouped shape pays the loads task 198 priced and nothing more, and in
  fact less than the six-group arm that recomputes the prefix.
* **The heavy shape is untouched**, as 11.3 requires: the sixty `make_date` outputs read 17.0 ns
  at 256 bits under the default and 17.1 with the budget off, 48.5 and 49.4 at 128, the same
  classes byte for byte; the every-group-split arm prices the blanket budget at 39.0 and 113.2,
  which is the sixty-group arm the ceiling already had. Prediction 2 holds by construction.
* **One arm at 128 bits landed slow: 265.8 ns a row for the ceiling-400 materialized kernel**,
  whose class is byte-identical to the every-group-split arm's (5.9 in the same JVM) and to the
  ceiling-200 and ceiling-100 arms' (5.9 each). Its four loop methods carry 86, 57, 57 and 57
  sites, all under the budget, and the same class in the 256-bit JVM read 3.3. The rate is what
  one of four groups in the slow mode and three fast would give - a quarter of the old one-group
  kernel's 900 plus the fast rest - so one loop method of one instance of the class stayed
  slow for the whole ten seconds of its case, in a JVM that had compiled nine other kernels of
  the two shapes first. The forked probe, one class per JVM, saw no slow fork in 120 under the
  budget at either width (12.1 and 12.2). Section 12.4 forks the sixty-four-tail class itself at
  both widths and repeats the narrow run three times, to say whether this is the class's or the
  JVM's history's - risk 1 of section 7, which the probe's fresh JVMs cannot see by design.

### 12.4 The slow arm's frequency: the class alone, and the benchmark's JVM repeated

Two readings of the one slow arm of 12.3, the same afternoon.

**The sixty-four-tail class alone**, `dev/varka_inlining_cliff.sh --outputs 64 --forks 10
--seconds 12`, at 128 bits and at the host's width: 0 of 10 forks slow at either, C1 compiling
every loop method in every fork, C2 once, settled at second 3 at 6.0 to 6.5 ns a row and at
seconds 2 to 3 at 3.4 to 3.6 - the benchmark's fast rates. Its methods carry 86, 57, 57 and 57
sites. With 12.1 and 12.2 that is 140 forks under the budget with no slow one.

**The benchmark's JVM repeated**, `dev/varka_bench_repeat.sh catalyst VarkaSharedPrefixBenchmark
3` at each width, the band files rewritten from the three runs
(`VarkaSharedPrefixBenchmark-jdk25-band.txt`, and a 128-bit band this benchmark did not have),
read beside the regeneration and the first three narrow repeats, so eight runs of the twenty
arms in all - the six under `dev/varka_bench_repeat.sh` unpinned from the committed files, the
per-run tables kept in the plan's record only through the bands:

| the cheap shape's arms, per row in ns | instances | fast | slow | the slow ones |
|---|---:|---:|---:|---|
| four groups under the budget, 256 bits | 40 | 39 | 1 | 207.9, the ceiling-400 recomputed arm in one repeat |
| four groups under the budget, 128 bits | 40 | 37 | 3 | 265.8 (12.3); 261.9 and 342.3, two arms of one repeat |
| six groups, ceiling 50 recomputed, both widths | 8 | 8 | 0 | - |
| one group, the budget off, both widths | 8 | 0 | 8 | 236 to 261 at 256 bits, 906 to 925 at 128 |
| the `make_date` arms, both widths | 80 | 80 | - | within 5% of the regeneration throughout |

The slow instances are one of five byte-identical classes in a JVM whose four siblings read
fast, never the same arm twice, and never the same arm at both widths; the fast instances read
3.3 to 3.6 and 5.9 to 7.6, the rates the class reads alone. So the budget's kernel is fast in
76 of 80 instances in a JVM of twenty kernels and in every one of 140 fresh JVMs, where the
one-method kernel it replaces is slow in 8 of 8 and in a fifth to a half of fresh JVMs (10.1 and
10.3). What remains is a slow mode of about one instance in twenty that the class does not
carry and a fresh JVM does not show - the JVM's history, risk 1 of section 7, which the
census's one-class forks could not see by construction and which `PLAN_TASK_198.md` 6 had
already met in this benchmark's six-group arm before the budget existed. That is milestone 7's
item 62, with the probe it needs.

**Predictions of 6.1, scored.** 1 holds: no slow fork in twenty at any count of the cheap shape,
at either width, in 140 forks. 2 holds by construction: no shape without a wide loop method over
the budget emits differently, the ladders and the corpus among them, and the `make_date` arms
read the regeneration's numbers in every run. 3 holds and then some: the regrouped shape pays
the prefix's stores and loads task 198 priced and nothing else measurable - 3.3 against about 4
for the one method that landed well, 4.0 for six groups - and the measurement adds what the
prediction did not name, the slow mode's residue in a JVM of many kernels.

### 12.5 Two more narrow runs, for the tiers and for the warm-up's exclusion

* **Under `-XX:+PrintCompilation`**, every loop method of every cheap arm went C1 at tier 3, then
  C2 at tier 4, then the tier-3 code made not entrant - the tiered path the budget is for - and
  one arm's four methods trapped once and came back through tier 2 to a second tier-4 compile.
  The compile log interleaves the case table, so that run's rates are unreadable and are not
  quoted; the tiers were the question.
* **With C1 excluded for the benchmark's classes** by a compiler directive - the warm-up's
  production directive (task 212), applied here without the warm-up - every cheap arm read 1076
  to 1551 ns a row, slower than the one-method kernel's 911, while the `make_date` arms read the
  regeneration's numbers to within 2%. A light method that C1 would compile, kept from C1 and
  fed only by its batches, does not reach C2 within a case of twelve seconds: the interpreter
  creates its profile late (`VarkaKernelCompileDirective`'s doc), where the heavy methods, past
  C1 either way, are unaffected. That is why the exclusion is added only for kernels the
  session warms, whose warm-up calls create the profile at once, and why it must stay tied to
  the warm-up; whether it should skip the methods under the budget altogether is item 61's
  third bullet, with this run as its first number.

## 13. The review, 29 September 2026

A code review of the pull request raised ten findings. Each was checked against the code and the
committed logs; seven changed the code or its documents, one was factually wrong in part, and
one asked for a change the rendering's contract forbids. None of the changes moves an emitted
byte for any shape this plan measured: the method tables of the cheap tails at 22, 48 and 64
outputs, the sixty `make_date` outputs and the size ladder's hundred entries, bytes and vector
call sites for every method, were captured with `dev/varka_emit.sh` before the first edit and
after the last, and are identical; `emitted_bytes.json` passes without regeneration. The
readings of section 12 therefore stand for the code as merged.

* **A budget that splits can make a class decline.** Each split gives the driver a call more in
  each form, so a wide shape whose driver is just under the byte budget would decline under the
  budget where the budget-off emitter emits. Fixed: when a class the call-site splits produced
  would decline, the emitter builds it again with the budget off, so the emission is exactly the
  budget-off one, decline or not, and the budget can never cost a kernel. A test forces the case
  with a byte budget between the one-group form's widest method and the one-output-a-group
  driver, and checks the answers.
* **Epilogues were outside the budget.** The reasoning that an epilogue "costs a little per
  batch" did not hold: the committed deopt logs show C1 refusing per-group epilogues, and an
  epilogue has no back edge, so it reaches C2 only by invocation count and runs interpreted on
  every batch that leaves a remainder for thousands of batches. Fixed: the budget reads a
  group's loop and epilogue methods alike, as the byte budget does, and the names lose the
  "loop" - the option is `VarkaEmitOptions.callSiteBudget` and the constant
  `VarkaEmitBudget.CALL_SITE_BUDGET`, where sections 11 and 12 say `loopCallSiteBudget` and
  `LOOP_CALL_SITE_BUDGET`. On every shape measured a group's epilogue carries exactly its loop's
  count, which the suite now pins, so no split moved.
* **The unit leaves out the masks' calls and the support helpers,** which C1 inlines and spends
  registers on too. True, and unmeasured: the evidence in hand points the other way - the
  `make_date` producer of 99 vector sites and about ten mask calls compiled in five forks of
  five where 99 cheap-tail sites were refused (12.1) - so the count is a proxy calibrated on
  two shapes, not C1's measure. Documented as such in the constant's doc; the census of a
  mask-heavy wide group, a split-condition filter's, is added to milestone 7's item 61 rather
  than the unit changed blind.
* **The warm-up's C1-exclusion doc contradicted the budget**, saying a kernel's loop methods are
  too large for C1. Fixed in the doc: the exclusion now covers methods C1 could compile, whether
  it should spare them is item 61's, and it must stay tied to the warm-up (12.5).
* **"512-bit hosts are unmeasured."** Wrong in part: the laptop's JVM prefers the 512-bit species
  (`Int512Vector` in the probe file's allocation sections), so the host-width forks read 512 bits
  and `--widths 16` read 128. The 256-bit species of the AVX2 runners is unmeasured, and item 61
  now says so; the constant's doc names the species it was read at.
* **Wasted rebuilds.** Half right. While a group is stuck on bytes the class declines whatever
  the call sites say, so reading them then only cost builds; fixed, they are not read. Splitting
  into as many pieces as a group's count needs, instead of halving, was not taken: the fused
  ceiling keeps a group's count near 180, so halving settles any group in two or three builds,
  and a different split rule would change the groupings section 12 measured.
* **Settings that emit identical code render different shape keys** - a heavy-group count of 0
  against 1, a call-site budget under the legacy form. No change: the rendering's contract is
  that distinct option values never collide, `VarkaShapeCacheSuite` holds every component to it,
  and these options are test-only. The docs now say that 0 and 1 alike split every group.
* **The probe script hardcoded 93** when the heavy-group arm was asked for without a budget.
  Fixed: the probe reads `default` for either argument as the production value, and the script
  passes that word.
* **The two group readers and the two split loops were copies.** Fixed: one reader over a
  per-method measure serves both budgets, and one halving helper serves both splits.
* **The corpus property counted only int-lane stores and drew only int-lane shapes.** Fixed: it
  counts the stores at both lanes and runs over four hundred shapes of each grammar, and a new
  test splits a wide long-lane group - forty outputs sharing a division by whole-node reuse -
  and checks its answers at two and eight lanes. The corpus still has no wide group: 0 of 800
  shapes regroup, 152 have a narrow one over the budget.

### 13.1 A warm-up suite that fails after the budget suite, on master too

The full Varka run after the review failed the warm-up suite's four compile tests: the warm-up ran
its kernel for its sixty seconds and it never stopped allocating. The kernel is the suite's own
three-output shape, a narrow group the budget never splits, so its class is the same with or
without this task. Run in the order `VarkaEmitterBudgetSuite` then `VarkaKernelWarmupSuite` in one
JVM, the four tests fail every time - on this branch before the review, after it, and on master
at `8047ea08f74`, which has no task 209 code - and the warm-up suite alone passes in twelve
seconds; ahead of it, either new budget test alone, either corpus test alone and either pair of
the new tests leave it passing. The full Varka run orders its suites by hash and puts the warm-up
suite seventh, before the budget suite, with the same six suites ahead of it on all three runs of
the day, of which it failed one: the full run after the review passed on its second try, all 457
catalyst tests. So the failure is not this task's: it is a kernel whose outcome
depends on what the JVM ran before it, the effect section 12.4 measured in the shared-prefix
benchmark, and a deterministic reproducer of it. It is recorded as item 62's first case.

### 13.2 The warm-up failure, diagnosed: a second int species in the shared test JVM

13.1's reading - the JVM's history in general, item 62's case - was wrong; the cause is narrower
and already in the record. Bisecting the budget suite ahead of the warm-up suite's first compile
test, one test at a time (a script over ScalaTest's `-z`), found one test enough: "under the byte
budget the epilogue is one method per group ... at both widths" (task 87, step 4), which runs its
answers at the host's width and at `lanesOverride` 4. Run at the host's width alone it leaves the
warm-up test passing; at 128 bits alone it fails it. So what breaks the warm-up kernel is an int
kernel of a second species, `Int128Vector`, run hot in the same JVM before the warm-up kernel is
compiled at the preferred 512-bit species.

The JVM's own output, a failing run against a passing one of the same warm-up test:

* **The heap and the code cache are not involved.** Live heap after each collection about 91 MB
  of 826 MB committed (a 4 GB maximum); code cache at most 28.6 MB of the test JVM's 128 MB; the
  run with a 512 MB code cache fails the same way.
* **The kernel compiles once and still allocates.** `LogCompilation`: C2 compiles the heavy loop
  once in both runs, no deoptimization, but to 37424 bytes of code in the failing run against
  3648 in the passing one, with half as many vector intrinsics again (131 binary operations
  against 101).
* **The allocation is a box per operation.** A JFR recording of the failing run: 8.5 GB of `int[]`
  in the kernel's frames on the warm-up thread, all at the intrinsic call sites in
  `IntVector.lanewiseTemplate` and `lanewiseShiftTemplate` reached from `add`, `mul`, `sub`, `min`
  and the shifts, inlined into the C2-compiled `loopDense0` and `loopMasked0` - the payload of a
  vector materialized as an object, about 1.1 GB every ten seconds.
* **The tell `vector-api-and-width.md` names.** The failing compile has 132 virtual calls to the
  shared `broadcast` and `lanewise` templates whose profile carries two receivers,
  `Int512Vector` at 115911 and `Int128Vector` at 47997; the passing compile never mentions the
  128-bit class. C2 inlines both species' bodies and the merge after them needs the vector as an
  object, which is the box. `-XX:-UseBimorphicInlining` does not help: the call then stays
  virtual, and its argument is boxed anyway.

This is the hazard `vector-api-and-width.md` records under "Every operator the plans rely on is
one instruction; two species in one JVM is a box per iteration" (`PLAN_TASK_28.md` 2.2), whose
rule is never to run
a second species of a lane type in a JVM shared with anything else, and whose note says the
catalyst harness is safe by construction. It is not: `VarkaEmitterBudgetSuite`,
`VarkaEmitterValiditySuite`, `VarkaCoverageCompositionFuzzSuite` and `VarkaEmitterDivisionSuite`
run kernels at a second width in the shared test JVM, and every kernel compiled after them there
may box. Their answers stay right, since a box is slow and not wrong, so the only in-process tests
that notice are those whose verdict is a JIT outcome - the warm-up suite's four compile tests.
The full Varka run passes or fails by how much 128-bit work ran before the warm-up kernel's
compile, which is what its hash order leaves to chance. Master has it too; it is not this task's,
and its fix is a change of its own. Item 62's own cases, the shared-prefix benchmark's JVMs, run one
species each, so that item's question stands.
