# VARKA-389: The warm-up releases every wide projection kernel, on every machine

## 1. Where this came from

Row 389 of `m7/PLAN.md`, opened on 18 October 2026 by the close (`VARKA-261.md` 5.5, prediction
4) on the owner's word: milestone 7 does not close with this regression in it, and the tag
`varka-m7` placed that morning on `793536853ab` moves to the commit that resolves this row.

The close regenerated `VarkaColdStartBenchmark` on the milestone 7 build, as every close does, and
found the warm-up's verdict RELEASED, the 60-second deadline reached with no verdict, on 51 of the
file's 71 verdicts. The committed file of 28 September (`5778ec020f1`, engine `0914c607ff8`), same
laptop, same eight-core pin, same JDK build string, had 71 COMPILED. Two "Run benchmarks"
dispatches on GitHub-hosted runners, an AMD EPYC 7763 and a Xeon 8370C, read exactly the same 51
of 71. The split is by shape on all three machines: every projection shape of the benchmark
(`greatest(add_months(d, k), date_add(d, k), last_day(d))` at 16 to 100 entries, in the
first-query, back-to-back and steady-state sections) releases; the two range-filter shapes compile
10 of 10. The files: `sql/core/benchmarks/VarkaColdStartBenchmark-jdk25-results.txt` (laptop, 18
October), `-runner-7763-m7-` and `-runner-xeon8370c-m7-` (the pair), against the same names at
`5778ec020f1` and `-runner-xeon8370c-` of 27 September.

The regression was invisible until the close because the laptop nightly has not run since 26
September, and because PR #900's runner pairs of 16 October reported the same verdicts as a
small-host effect, which became `m9/SCOPE.md` item 103. Item 103 now carries the cross-machine
numbers and points here.

## 2. How much

Varka with the warm-up, 28 September against 18 October, the laptop:

| Row | 28 Sep | 18 Oct | Change |
| :--- | ---: | ---: | ---: |
| Once compiled, 16 entries, 100K rows, best | 27 ms | 35 ms | 1.3x slower |
| Once compiled, 54 entries | 26 ms | 53 ms | 2.0x |
| Once compiled, 100 entries | 42 ms | 81 ms | 1.9x |
| Back to back, median of queries 2 to 15, 54 entries | 128 ms | 187 ms | +46% |
| Back to back, 100 entries | 240 ms | 303 ms | +26% |
| Steady state at 2M rows, straight after the verdict, 16 entries | 48 ms | 125 ms | 2.6x |
| Steady state at 2M rows, 100 entries | 250 ms | 621 ms | 2.5x |
| Steady state, after two seconds of its own batches, 16 entries, average | 60 ms | 1,639 ms | 27x |

The Xeon 8370C runner, 27 September against 18 October: once compiled 37 to 64 ms at 16 entries
and 72 to 193 ms at 100; steady state at 2M rows 87 to 336 ms best at 16 entries and 520 to 2,062
at 100; the "own batches" average at 100 entries 1.7 s to 36 s.

Per query at 100K rows Varka still beats stock (187 ms against 428 at 54 entries), so a user sees
a smaller win, not a loss. At 2M rows the kernel runs 2.5x to 4x slower than its 28 September
self, the gap between code with its vector operations scalarised and code that boxes them, and
the averages with multi-second spikes read like a kernel that keeps deoptimising. The night's
surfaces, single-expression projections over a billion rows, read 11x to 54x against stock with
medians in line with 5 October (`VARKA-261.md` 5.5, prediction 1), so this is the wide,
multi-entry shapes, not steady-state kernels in general.

## 3. What the verdict measures

`VarkaKernelWarmup.run` does not ask the JIT whether a method is compiled. It calls the kernel in
blocks, then runs probe calls under `VarkaAllocationSampler` and compares the bytes they allocated
with an allowance (a fixed part, a part per probe row, a part per column per call); a kernel whose
probe stays within the allowance for `CLEAN_PROBES` probes in a row is COMPILED, and one whose
probe is still allocating at the deadline is RELEASED, with a reason string the benchmark does not
print. So RELEASED means "this kernel's calls still allocate after 60 seconds": either a loop or
epilogue method is not yet in C2 code, or it is, and the code still boxes vectors, or the call
path allocates something the allowance does not cover. The benchmark's call counts fit a kernel
running interpreted or boxed for the whole minute: about 56,000 calls in 60 seconds at 16 entries
on the laptop (VARKA-295's released outcomes read the same, about 62,000) against 13,500 calls in
1.9 seconds for the 28 September COMPILED verdict.

## 4. First findings, 18 October

Read with `dev/varka_emit.sh` on the 16-entry shape (`VARKA-389` scratch log `e1-emit16.log`) and
a smoke run of the benchmark (`VARKA_COLDSTART_SMOKE=true`, rung 16 only) under
`-XX:+PrintCompilation` with `-Dvarka.warmup.deadlineSeconds=10` (`e2-smoke.log`):

1. **Not HugeMethodLimit.** The kernel is one class of 16 group methods, four groups of four
   outputs, each group a dense and a masked loop with an epilogue each, 3,098 to 3,820 bytes of
   bytecode; every method is under 8,000.
2. **Every group method is past C1.** Each carries 379 to 430 Vector API call sites against the
   call-site budget of 93 (`VarkaEmitBudget.CALL_SITE_BUDGET`), and the emitter leaves them
   there by design: a group of at most `HEAVY_GROUP_OUTPUTS` (6) outputs may keep a method over
   the budget, on VARKA-209's census that "narrow groups settled within seconds and did not
   cycle". These four-output groups do not settle.
3. **The directive works as written.** The warmed class's methods are excluded from C1 ("made
   not compilable on level 3 ... excluded by CompileCommand") and C2 is asked for all sixteen
   within three seconds of the warm-up's start (JVM time 42.1 to 44.8 s for a warm-up released
   at about 52 s). So the profile is created in time and the compiles are requested in time;
   what is open is whether they finish and what they produce.
4. **C2 is near its limits on these methods.** An OSR compile of `loopMasked2` ends with
   `COMPILE SKIPPED: out of nodes during split`. A method of 400 Vector API call sites inlines
   hundreds of template bodies; past C2's inlining cutoffs the remaining calls stay calls, and a
   Vector API call that is not inlined is not intrinsified: it boxes. That is one mechanism that
   gives both symptoms at once, a probe that never comes clean and compiled code 2.5x to 4x
   slower.
5. **The shape carries VARKA-379's guard.** Every output's IR reads the date column through
   `(guardedRange col:0 -719162 2932896)`, the per-batch range check merged on 17 October
   (#874), three times per output. Whether that guard is what pushed these groups past the cliff
   is the first bisection point.

## 5. The investigation

In order, each step cheap enough to run inside the close's day:

1. The smoke rung at `78930efbbe3`, the commit before VARKA-379, same flags: COMPILED or RELEASED.
2. A flight recording of the master smoke rung (`jdk.Compilation` events for the sixteen methods,
   their durations and whether any fails, and the `org.apache.spark.sql.varka.KernelWarmup`
   event) with `-XX:+PrintInlining` filtered to the kernel class: which call sites C2 declines to
   inline, and why.
3. The emit tool at `78930efbbe3` and at `0914c607ff8` (28 September): the same shape's groups
   and call sites then, so the grouping's history is read rather than guessed.
4. The fix direction, priced: `heavyGroupOutputs` lowered so that groups of this weight split,
   measured on the smoke rung (verdicts, once-compiled and steady-state rows) and then on the
   full benchmark at both widths and a runner pair; or, if step 2 shows the compiles finishing
   with their templates inlined, the allocation the probe sees and where it comes from.
5. A check that would have caught this: the warm-up verdict of the 16-entry shape in a suite, on
   a runner, with a bound on the time to COMPILED, so a change that pushes a group past the
   cliff fails its pull request rather than the next close.

## 6. Predictions, registered before the run

1. The smoke rung at `78930efbbe3` reads COMPILED on the 16-entry shape (VARKA-379 is the
   change), with probability 0.5; if not, the bisection lands on one of VARKA-254 (`b5d1c36f22a`,
   grouping weights), VARKA-296 (`b72d71d727f`) or VARKA-86 (`b2e5ef370dc`), 0.35 together.
2. The flight recording shows every one of the sixteen tier-4 compiles finishing within the
   10-second window, so the release is about what the code does, not about the compile queue:
   0.6.
3. `-XX:+PrintInlining` shows Vector API call sites in the group methods declined for size
   (`NodeCountInliningCutoff`, `size > DesiredMethodLimit` or "already compiled into a big
   method"), where the range-filter kernel's methods show none: 0.6.
4. With the heavy groups split (`heavyGroupOutputs` at 1, or the budget applied to them), the
   smoke rung reads COMPILED within 5 seconds and the once-compiled row returns within 15% of
   28 September's 27 ms: 0.6.
5. The 28 September engine's emit of the shape has more groups and fewer call sites per method
   than today's, by at least a factor of two: 0.6.
6. The fix costs the steady-state throughput of narrow heavy shapes nothing measurable (the
   hold rule, `VARKA-261.md` 8): the full cold-start file after the fix has every row within 10%
   of 28 September's or better, and the surfaces' `date_add(d, 3)` row unchanged: 0.7.

## 7. Done when

The cause is named with the commit that introduced it; the fix is merged with
`VarkaColdStartBenchmark` regenerated on the laptop at both widths and a runner pair, every
projection shape COMPILED and the once-compiled rows within 15% of 28 September's; the README's
and `docs/sql-varka.md`'s cold-start row requoted from those files; a check in the suites that
fails when the 16-entry shape's warm-up does not reach COMPILED on a runner; `m9/SCOPE.md` item
103 rewritten to what remains of it, if anything; and the close (`VARKA-261.md`, #919) updated,
the tag moved to the fix's merge commit.

## 8. Outcome

### 8.1 The runs of 18 October, in order

Every run is `VarkaColdStartBenchmark` in its smoke form (rung 16 of each section) with
`-Dvarka.warmup.deadlineSeconds=10` and `-XX:+PrintCompilation`, on the laptop under the heavy
lock, unless said otherwise; "verdict" is the first-query section's warm-up verdict on the 16-entry
shape, and the range-filter shapes compiled in every run.

| Run | Engine | Verdict | What it settles |
| :--- | :--- | :--- | :--- |
| E2 | master (`793536853ab`) | RELEASED | the regression reproduces in 3 minutes |
| E4 | `78930efbbe3`, before VARKA-379 | RELEASED | VARKA-379 is not the cause |
| E5 | `0914c607ff8`, 28 September | COMPILED, 2.1 to 2.7 s | the machine is not the cause |
| E6 | `0a9ce088e21`, Task 209, 29 September | COMPILED, 1.8 to 3.1 s | the directive and the call-site budget are not the cause |
| E10 | master, the directive switched off | RELEASED | the directive is not the cause, alone or with master's code |
| E7 | master, `HEAVY_GROUP_OUTPUTS` 1 | COMPILED, 2.4 to 3.3 s | splitting the four-output groups restores the verdict |
| B2 | `b72d71d727f`, VARKA-296, 9 October | COMPILED, 2.1 to 2.4 s | the change is between 9 and 16 October |

The emit tool on the shape (`dev/varka_emit.sh`, sixteen `greatest(add_months(d,k),
date_add(d,k), last_day(d))` over `d:date`) at four engines: four groups of four outputs
everywhere; the dense loop methods 3,220 bytes and 402 Vector API call sites on 28 September,
3,338 and 408 at Task 209, 3,294 and 408 before VARKA-379, 3,379 and 414 on master; no mask
operation in a dense method until VARKA-379's `guardedRange` adds four. So prediction 5 fails:
the methods' size and call-site count did not move between a kernel that compiles and one that
does not.

### 8.2 What the flight recording says

A profile recording of the master smoke run (E2b), read with `jfr`:

* Every one of the eight loop methods of the warmed class compiles at tier 4, successfully, in
  1.1 to 3.7 s, within three seconds of the warm-up's start, with 35 to 39 KB of bytecode inlined
  into each and 265 to 634 KB of machine code out; the C2 queue drains to zero every five
  seconds and the run's longest compile is 3.71 s. Prediction 2 holds: the release is not about
  the compile queue.
* The allocation samples of the warm-up window sit in the loop methods, and keep landing there
  after their compiles finished: about 120 samples per loop method in the four seconds after the
  last compile. Their frames are the Vector API's boxed fallbacks, `IntVector.lanewiseTemplate`
  under `IntVector.add(int)` at the kernel's literal-operand lines, `lanewiseShiftTemplate`,
  `uOpTemplate`, `blendTemplate`, `bTest`. So the compiled loops still run part of their vector
  work through the fallbacks, which is the allocation the probe sees and the 2.5x to 4x at
  volume.
* The deoptimisations of the run (876) are not the kernel's: four, all in
  `prepareOutputValidity`, a per-batch driver helper. The averages that spike to seconds are not
  a deopt storm of the kernel.

The split run (E7) shows the same C2 finishing the job when each method carries one output
instead of four: the fallbacks are what C2 leaves behind in a four-output body of master's
emission, and left nothing behind in a four-output body of 9 October's. Prediction 3 was
half right: C2 declines call sites in these bodies, but the declined ones in the log are the
cold error paths (`AbstractSpecies::checkFailed`, 5,000 times) and a handful of sites with "no
static binding", not a wholesale cutoff.

### 8.3 The commit: VARKA-295's template warm-up, `aa457c3797b`, 15 October

Two more bisection points and two emits closed the window, and then a switch named the commit
without a build:

| Run | Engine | Verdict | What it settles |
| :--- | :--- | :--- | :--- |
| B4 | `e1cbfea709d`, VARKA-279, 12 October | COMPILED, 2.2 to 2.3 s | VARKA-86 and VARKA-279 are not the cause; the window is 12 to 16 October |
| emits | 9 October, 12 October, before VARKA-379 | identical tables | the emitted class did not change in the window |
| E12 | master, `-Dvarka.warmup.vectorTemplates=false` | COMPILED, 1.6 to 1.7 s | the Vector API template warm-up is the cause |

The emit tables at the good 9 October and 12 October engines and at the bad 17 October engine
agree to the byte: `loopDense0` 3,294 bytes, 408 Vector API call sites, no mask operation, the
drivers 196 bytes, `run` 66. So the kernel the JIT is given is the same before and after the
regression, and the change is in what the JVM knows when it compiles it. The one change in the
window that touches that is `aa457c3797b`, "[VARKA-295] Warm the shared Vector API templates
before the first kernel (#827)", merged 15 October: `VarkaVectorTemplateWarmup`, run once per JVM
on the warm-up thread before its first job, calls every Vector API template the kernels use 300
times with every operator class interleaved, so that the templates' call sites are profiled as
megamorphic and the hottest templates are compiled by C2 from that mixed profile
(`VARKA-295.md` 9.7). It ships with a property that turns it off, for measurements. Master with
that property reads COMPILED on the 16-entry shape in 1.6 and 1.7 s, the range filters in 130 to
145 ms, and the steady-state row straight after the verdict at 29 ms best, where master reads
125 ms and the file of 28 September 48 ms. Prediction 1 was wrong twice over: not VARKA-379, and
not one of the three it named second.

### 8.4a The mechanism, from the JIT's log (follows 8.3; supersedes 8.4 below where they differ)

Two smoke runs of master under `-XX:+LogCompilation`, one with the template warm-up on (E13,
RELEASED) and one with it off (E15, COMPILED), read at the first tier-4 compile of the warmed
kernel's `loopDense0` and at every uncommon trap whose innermost frame is a shared template:

| | warm-up on | warm-up off |
| :--- | ---: | ---: |
| inlined copies of `LambdaForm$Holder.constant_L` / `ImplCache.find` / `Int512Vector.lanewise` | 684 / 348 / 598 | 675 / 343 / 590 |
| decompile counts on inlined templates | `IntVector.lanewise` 1, others | none |
| the compile's cumulative decompile sum crosses 201 | at parse 24,100 of 120,000, inside `IntVector.lanewise` | never |
| virtual calls left in the compile | 367 (304 in `lanewise`, 25 `add`, 23 `sub`, 6 `max`) | 0 |
| uncommon traps with a template as innermost frame, whole run | 8, all `unstable_if` / `reinterpret`, all in the same second, the 38th | 0 |
| first standalone tier-4 compile of `IntVector.lanewise` | the 14th second | the 14th second |

So the two JVMs compile the same bytecode with the same inlining, and differ in one thing: in the
warm-up arm the shared templates carry a decompile each. Where it comes from is the sequence. In
both arms the benchmark's first Varka arms run without a warm-up and their interpreted calls take
`IntVector.lanewise`, `Int512Vector.broadcast` and the operator cache past C2's invocation
threshold, so they are compiled standalone in the 14th second from the profile of one kernel's operators. In
the warm-up arm, the template warm-up then runs in the 38th second, when the first warmed shape queues its
job, and its 300 rounds with every operator class interleaved take branches those standalone
compiles never saw: `ImplCache.find` at bci 10, `lanewise` at bci 18 and 28 trap `unstable_if`
with action `reinterpret`, which deoptimises the nmethod and adds one to each method's decompile
count. The kernel's loop compiles seven seconds later, inlines `lanewise` about six hundred times,
and C2 adds that one decompile once per copy (`Parse::Parse` sums each parsed method's
`MethodData` counts into the compile): the sum reaches 201 a fifth of the way through and from
there C2 keeps a real virtual call on every class-check miss instead of an uncommon trap
(`VARKA-295.md` 9.6). The vector joining from such a call is not exact, the intrinsic refuses it,
and one `int[16]` per iteration survives: the allocation the probe sees, and the boxed `add`,
`sub` and `max` under which every allocation sample sat.

This is why the cut is by width and not by machine: a group method of one output inlines each
template about a hundred times and stays under 201 with the same decompile (E7 compiled clean
with the warm-up on); four outputs inline it six hundred times. And why 15 October is the date:
before #827 nothing deoptimised the templates in a one-shape JVM. The template warm-up was built
on a JVM in which it ran before any template was compiled, VARKA-295's fuzz arm, and there it
zeroed the class-check traps it was aimed at; in a JVM where vector code ran first, it is what
deoptimises the templates. The round count does not matter: 100 rounds release the same way
(E14). The benchmark takes that order by design, its unwarmed arms before the warmed one, and a
Spark executor takes it whenever the query-path helpers (`IntRangeOps`, `SelectionVectorOps`,
which 9.7 noted the warm-up does not cover) or a session without the warm-up run vector code
before the first warmed shape.

### 8.4 The mechanism, as far as today's evidence reaches (written before 8.4a)

`VARKA-295.md` 9.6 established, from the JIT's own log, why a compiled kernel loop still boxes: a
C2 compile sums, once per inlined copy, the class-check trap counts and the decompile counts a
shared method's `MethodData` holds, and past 100 traps (`PerMethodTrapLimit`) or 201 decompiles
(`PerMethodRecompilationCutoff`/2 + 1) it stops putting uncommon traps on class-check misses and
keeps a real virtual call; the vector that joins from such a call is not exact, the `binaryOp`
intrinsic refuses it, and one `int[16]` per iteration survives escape analysis. The counts come
from the templates' standalone C2 compiles meeting another operator class and deoptimising. The
template warm-up was built to zero those counts by making the templates' sites megamorphic before
any standalone compile could speculate; in its reproducer, a JVM of hundreds of narrow shapes, it
did.

What it does to a wide shape is the other side of the same sum. A four-output group method of
this kernel inlines each template hundreds of times (`VectorOperators.opKind` 170 times in
VARKA-295's released compile, with 408 Vector API call sites per method here), so any count the
templates carry is multiplied by hundreds, where a narrow kernel multiplies it by tens. The
warm-up compiles the hottest templates standalone during its 300 rounds, and a template compiled
standalone and then made not entrant, by a species or a class the warm-up did not feed it (9.7
saw `VectorMask.fromLong` do exactly that), carries a decompile count from then on. One such count
per copy, over hundreds of copies, is past 201 in the wide kernel's compile and under it in the
range filter's, which is why the split is by shape, the same on every machine, and absent before
15 October. The compilation log of the master smoke run (E13, in progress as this is written) is
to show the sums themselves; what is already shown is the cause and its switch.

Three further facts fit. The 4-core runners' "20 of 25 shapes" of 16 October (item 103) were
#900's pairs run the day after #827 merged. The night's surfaces, single-expression kernels, were
unaffected because they inline each template a handful of times. And splitting the groups to one
output (E7) restored the verdict with the warm-up on, by dividing the multiplier by four.

### 8.5 Predictions scored

1. **Missed.** Not VARKA-379, and not VARKA-254, VARKA-296 or VARKA-86; the bisection landed on
   VARKA-295's #827, which the list did not name.
2. **Held.** Every tier-4 compile finished in 1.1 to 3.7 s; the C2 queue was empty at every
   sample.
3. **Half held.** C2 declines call sites in these bodies, but not for size: the declined sites
   that matter are the virtual calls it keeps where a profile sum is past its limit.
4. **Held in part, and overtaken.** The split groups compile in 2.4 to 3.3 s, but the switch
   that names the cause compiles the unsplit kernel in 1.6 s, so the split is not the fix.
5. **Missed.** The 28 September engine emits the same four groups and 402 call sites per method.
6. **Open.** The fix is not chosen yet; the switch arm's steady-state row (29 ms against 48 on 28
   September) says the hold rule's bound is reachable.

### 8.6 The fix: the options, with what each has measured

The owner decides; the row's recommendation is the first, with the third as the measured
fallback.

1. **Run the template warm-up before any vector code, not before the first warmed shape.** The
   warm-up only harms templates that are already compiled when it runs; VARKA-295 measured it on
   a JVM where it ran first and found it exactly right there. Moving `ensure()` to the engine's
   first use (the shape cache's or the vector helpers' first touch, synchronously, once per JVM,
   200 to 600 ms on that thread) keeps 295's benefit in every JVM and removes this regression in
   every JVM, benchmark included. Measured (E17, `templateWarmup()` called at the shape cache's
   first `build`, 300 rounds, nothing else changed): the 16-entry shape COMPILED in 1.5 s twice,
   the fastest verdict of the day, the range filters in 125 to 141 ms, the steady-state row
   straight after the verdict at 34 ms best, the once-compiled row 45 ms. What remains for the
   fix is the full file at both widths and a runner pair, and the order of the warm-up against
   the query-path helpers that run vector code without a kernel.
2. **Off by default.** Measured: the 16-entry shape compiles in 1.6 s, steady state 29 ms (E12).
   Returns VARKA-295's many-shape releases (3.6 to 8.2% of warm-ups in its fuzz arm), which 8.4a
   now reads as the same sum reached by other traps in a long-lived JVM.
3. **Raise the JVM's limits**, `-XX:PerMethodRecompilationCutoff` and `-XX:PerMethodTrapLimit`.
   VARKA-295 measured both at 100,000 as 0 releases in 1,000. Measured here (E16, both at
   100,000, warm-up on): the 16-entry shape COMPILED in 1.4 and 1.9 s, the range filters in 150
   to 165 ms, and the steady-state row straight after the verdict at 16 ms best, the fastest of
   every arm run today (29 off, 48 on 28 September, 125 master). A global JIT policy every
   deployment would set beside `--add-modules jdk.incubator.vector`; it also covers any later
   cause that adds a count to a shared template, which the first option does not, and it is one
   smoke run's evidence, not a pair's.
4. **Fewer rounds.** Measured and rejected: 100 rounds release the same way (E14), since the
   harm is the deoptimisation, not the compile.
5. **Narrower groups** (`heavyGroupOutputs` at 1, E7). Compiles with the warm-up on, by dividing
   the multiplier; costs calls and prefix loads per batch for every wide shape, for good, and
   leaves the sum one more decompile away from the cutoff.

Whichever is chosen, the row adds the check that would have caught this: the 16-entry shape's
warm-up verdict in a suite that first runs an unwarmed kernel, so the benchmark's order is the
test's order, on a runner, with a bound on the time to COMPILED.

## 9. The fix: the template warm-up before any vector code

Chosen by the owner on 18 October 2026, the first option of 8.6.

### 9.1 The change

`VarkaVectorTemplateWarmup` gains `beforeVectorCode()`: one volatile read once the warm-up has
decided for the JVM (ran, switched off or failed), `ensure()` the first time, never throwing. It
is called from the four places where the engine's vector code can first run in a JVM, so that
whichever arrives first runs the warm-up on its own thread, once:

* `VarkaShapeCacheImpl.build`, the driver's first emission, before any emitted class exists;
* `VarkaShapeEntry.newKernel`, the executor's first kernel instance of a task;
* the class initialisers of `IntRangeOps` and `SelectionVectorOps`, the query-path helpers that
  run vector code without a kernel, which VARKA-295's 9.7 noted its warm-up did not cover;
* the warm-up worker before its first job, as before, now a no-op in a JVM where any of the
  three ran first.

`ensure()` records what the run took and logs it once; `VarkaKernelWarmup.templateWarmupNanos()`
reads it from there. The property `varka.warmup.vectorTemplates=false` still switches the warm-up
off, and a JVM that decided off stays off. Nothing else changes: not the rounds, not the
operators, not the species the warm-up feeds, not the kernels.

The cost moves with it: 200 to 600 ms once per JVM, on the first query's planning thread on a
driver and on the first task's thread on an executor, where VARKA-295 had it on the worker thread.
The owner accepted that in choosing the option; a later row can take it back to a background
thread if it learns to run before the helpers without a race.

### 9.2 The checks

* `VarkaVectorTemplateWarmupSuite`, "beforeVectorCode runs the warm-up once, on the caller's
  thread, then is settled": the hook runs the warm-up on first call, is a no-op after, settles
  without running when the property says off, and stays off.
* `VarkaKernelWarmupSuite`, "a wide shape warmed after an unwarmed kernel took the templates to
  C2 still compiles": the benchmark's order as a test, in a JVM of its own. The suite's forked
  probe (`VarkaKernelWarmupProbe`) gains a `wide` mode: it emits the benchmark's shape at sixteen
  entries, runs a kernel of it unwarmed for 600 batches, dense and masked in turn, so that the
  interpreted calls take the shared templates to their standalone C2 compiles, invalidates the
  cache, and warms a new class of the same shape; the test asserts the verdict COMPILED. A first
  version of this check inside `VarkaWarmupEndToEndSuite` passed on master's engine as well,
  because that suite's earlier tests had already run the template warm-up in the test JVM before
  the unwarmed kernel, the fixed order by accident; a check for an order has to own its JVM, which
  the probe does. It runs on every pull request's Varka suites.

### 9.3 Outcome, 18 October 2026

* **The suites.** `VarkaVectorTemplateWarmupSuite` and `VarkaWarmupEndToEndSuite`, 12 tests
  pass. `VarkaKernelWarmupSuite`'s new forked check passes on the fix in 16 s, the verdict
  COMPILED; against master's engine, the same probe and suite copied into a worktree of master,
  it fails in 2 minutes 14 seconds, the verdict RELEASED at the probe's 120-second deadline. So
  the check tells the two engines apart, which the in-suite version could not.
* **The cold-start file, both widths, on the laptop** (`dev/varka_bench_regen.sh core
  VarkaColdStartBenchmark`, canary ok, the invariants gate ok): 71 of 71 verdicts COMPILED at
  512-bit lanes and 71 of 71 at 128-bit, where the morning's file had 20 and 51. Against the
  file of 28 September, same machine and pin: the steady-state rows at 2M rows straight after
  the verdict read 41, 105 and 186 ms at 16, 54 and 100 entries against 48, 133 and 250, so the
  2.5x to 4x of the regression is gone and those rows are 15 to 25% better than the file the
  regression was measured against; the "once compiled" rows read 31 ms at 16 entries against 27
  and 57 ms at 100 against 42; the first run of the 16-entry shape 137 ms against stock's 119,
  the second 69 against 49.
* **The three files side by side**, the laptop, same machine and pin, the rows the post draws:

  | file | COMPILED of 71 | steady state at 2M rows, 16 / 54 / 100 entries, ms |
  | :--- | ---: | :--- |
  | 28 September (`5778ec020f1`) | 71 | 48 / 133 / 250 |
  | 18 October, master (`793536853ab`) | 20 | 125 / 324 / 621 |
  | 18 October, the fix | 71 | 41 / 105 / 186 |

* **A residual, not the warm-up's.** The back-to-back section's warmed arm reads 184 ms a
  query at 54 entries and 336 at 100 against 28 September's 128 and 240, 40 to 44% slower,
  while at 16 entries it reads 49 against 47, and the same section's unwarmed Varka arm and the
  stock arm are within 1% of 28 September at every rung. So the warmed class of a wide shape
  pays something per query at 100K rows that the unwarmed class does not and that 2M rows do
  not show, on master as on the fix (master's file reads 187 and 303 on those rows). It is not
  what this row was opened for and it predates the template warm-up; it is left to the owner as
  9.4 says, with the measurement that would find it.
* **The runner pair**: two "Run benchmarks" dispatches of the class on the fix branch as #921
  opened; their verdicts are added here when they land.
* **The README's and the docs' cold-start row**, requoted from the new file: 1.6x once the
  warm-up's kernel is in (31 against 49 ms best), the first run at 0.9x on the row path, the
  verdict in about two seconds; `dev/varka_quote_check.py README.md` finds 0 orphans.

### 9.5 A second, older failure on four cores, found by the pull request's own CI

The fix's Build failed its Varka sql suites job: two tests of `VarkaWarmupEndToEndSuite`, the
ones VARKA-295 was opened on, RELEASED at the 120-second deadline on the runner, with the JIT's
account showing the heavier loop method compiled in two seconds and still boxing. That job runs
the suite alone in a quiet JVM, on a 4-core runner whose CPU the pool draws; the account named
`Int256Vector`, so an AVX2 host. On the laptop the suite had passed in a fresh JVM on 24 cores,
at 256-bit lanes too, the test JVM's preferred species here.

* **Reproduced, and not this change's.** The suite pinned to four cores with
  `-XX:MaxVectorSize=32` fails the same two tests on the fix and on master's engine alike (G1,
  G2), and passes or fails from one run to the next on the same code: of nine runs of the
  committed fix and its paced variants on the same four cores, four passed every test and five
  released the same two. The job's pass of 17 October was a draw of an AVX-512 runner. It is item
  103's "small runner" question, now on master itself, and a row of its own (row 390 proposed).
* **Not the sums of 8.4a.** The warmed kernel's `loopDense1` compile in a failing run (G3) has
  no trap or decompile count and crosses nothing. It keeps three virtual calls, at the inner
  `lanewise` call of `IntVector.min(Vector)` twice and of `IntVector.max(Vector)` once, each
  `<call ... count='-1'>`: C2 found no mature profile in the template it had inlined. The
  passing run's compile of the same method (G13) keeps none.
* **The mechanism, from the policy's own trace.** `-XX:+PrintTieredEvents` prints every
  decision of `CompilationPolicy` with both queue lengths, the threshold scale `k` and the
  method's counts; two traced runs of the suite on four cores, one passing (G13) and one
  failing (G14a), differ in one instant. The template's first compile does not fire at 200
  calls: C1's queue scales the tier-3 thresholds (`threshold_scale`, `k = queue / (5 x C1
  threads) + 1`, one C1 thread on four cores), and the trace shows `k` between 1 and 6.6 while
  Spark's first query heats up, so `max` reports at 128, 256, 384, 512 and 640 calls without
  crossing `200 x k`. When it does cross, `transition_from_none` reads C2's queue: at most ten
  entries (`Tier3DelayOn` 5 times two C2 threads) and the method goes to tier 3; longer and it
  goes to tier 2, C1 code with counters and no profiling. In the passing run `max` crossed at
  640 calls with C2's queue at 7 and went to tier 3, its profile was fed to 23,552 calls, and
  C2 compiled it from that. In the failing run it crossed at 768 calls with C2's queue at 17 and
  went to tier 2; its profile, created by the interpreter at 256 calls, froze at 641; at its
  first tier-2 report, 2,689 calls, the policy sent it straight to tier 4 (`compile level=4`,
  queues 5 and 19), and tier-4 code feeds no profile either. C2 trusts a profile only past
  `ProfileMaturityPercentage` (20%) of the tier-4 count, 1,000 calls; at 641 the kernel's
  compile read `count='-1'` at the template's inner call, kept the virtual call, and a vector
  that joins from a virtual call is not exact, so the intrinsic refuses it and one vector boxes
  per iteration. `min(Vector)` took the same path (tier 2 at 640 calls, queue 12; tier 4 at
  3,244, profile frozen at 1,196); `min(int)`, which took tier 2 at 512 calls, was sent to tier
  3 at its first report (2,739 calls, past the policy's "old" mark of 2,000, which turns the
  queue feedback off), and the kernel kept no virtual call at its sites.

| run | first compile at | `k` then | C2 queue then | tier | profile when C2 compiled it | kernel's virtual calls |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| G13, passed | 640 calls | 1.6 | 7 | 3 | 23,552 calls | 0 |
| G14a, failed | 768 calls | 1.0 | 17 | 2 | 641 calls | 3 (G3's compile) |

* **Why the warm-up's rounds are not where it happens.** The rounds call `max(Vector)` and
  `min(Vector)` once each, 300 calls in all, under the scaled thresholds; their first compile
  fires 200 to 300 ms after the rounds end, during the kernel warm-up that follows, when C2's
  queue holds the kernel warm-up's and Spark's own compiles, 11 to 26 entries in the traces.
  Whether the instant finds the queue above ten is the coin.
* **What was tried, and falsified.** Pacing the rounds on C1's queue (G4) changed nothing.
  Pacing them on C2's queue, read through the `Compiler.queue` diagnostic command, every 25
  rounds (G6) released; every round to the policy's limit of ten passed five of five (G7) and
  four of five (G8); every round to the policy's resume limit of four (`Tier3DelayOff` times
  the threads) released two of five in three runs of three (G9 to G11), with the queue at most
  7 through every round and the hold costing 203 to 288 ms, and the projection test alone
  under the same pacing passed (G12) with `max` at tier 2 then tier 4 all the same. The hold
  cannot reach a compile that fires after the rounds; the passes were the coin. The pacing is
  not in this change. `-XX:Tier3DelayOn=1000`, which forbids tier 2 JVM-wide, passed once (G5),
  as the mechanism predicts, and is a flag for every method.
* **What would fix it, for the row.** Make the templates' first compile fall inside the
  warm-up and at tier 3: enough calls per template to cross `200 x k` during the rounds, or to
  pass the policy's old mark of 2,000, after which `transition_from_none` ignores C2's queue
  and `select_task` cannot drop the method's task as stale; then hold the rounds until the
  templates show at tier 3 or 4 in `Compiler.codelist`. Measured on four cores with
  `-XX:+PrintTieredEvents`, ten runs, and at both lane widths on the laptop so the wide shapes
  of 8.4 stay compiled.
* **The pair of #921 on the runners**, dispatched before any of this: an AMD EPYC 9V74 (512-bit
  lanes) and an AMD EPYC 7763 (256-bit lanes, four cores, the suite's failing kind of host)
  both read 71 of 71 COMPILED in the benchmark's JVM. The benchmark's kernels come after the
  session has cached its tables and C2's queue has drained; the suite's first warm-up comes a
  second after the session starts. The order of events in a JVM is the variable, as it was in
  8.4a.

The compile tiers of two JVMs of the suite, from their logs, every nmethod of the run:

| tier | four cores, 256-bit lanes | twenty-four cores |
| :--- | ---: | ---: |
| 0, native wrappers | 728 | 789 |
| 1, C1 without profiling | 1,564 | 3,853 |
| 2, C1 with counters only | 1,242 | 252 |
| 3, C1 with full profiling | 6,595 | 19,648 |
| 4, C2 | 2,007 | 6,296 |

### 9.6 Every arm of the day, in one table

The 16-entry shape's warm-up verdict (the end-to-end suite's 3-output shape where marked):

| arm | host | verdict |
| :--- | :--- | :--- |
| master, 18 October | laptop, 24 cores | RELEASED |
| before VARKA-379 | laptop, 24 cores | RELEASED |
| 28 September engine | laptop, 24 cores | COMPILED |
| Task 209, 29 September | laptop, 24 cores | COMPILED |
| 9 October (VARKA-296) | laptop, 24 cores | COMPILED |
| 12 October (VARKA-279) | laptop, 24 cores | COMPILED |
| master, directive off | laptop, 24 cores | RELEASED |
| master, 100 rounds | laptop, 24 cores | RELEASED |
| master, groups of one output | laptop, 24 cores | COMPILED |
| master, template warm-up off | laptop, 24 cores | COMPILED |
| master, JVM limits raised | laptop, 24 cores | COMPILED |
| the fix (warm-up before any kernel) | laptop, 24 cores | COMPILED |
| the fix, cold-start file, both widths | laptop, 8 cores | COMPILED 71 of 71 |
| the fix, runner pair | EPYC 9V74, 4 cores | COMPILED 71 of 71 |
| the fix, runner pair | EPYC 7763, 4 cores | COMPILED 71 of 71 |
| master, end-to-end suite | laptop, 4 cores, 256-bit | RELEASED |
| the fix, end-to-end suite (G1; G13 passed, G14a released) | laptop, 4 cores, 256-bit | RELEASED |
| the fix, end-to-end suite, `-XX:Tier3DelayOn=1000` | laptop, 4 cores, 256-bit | COMPILED |
| the fix, paced on C1's queue, end-to-end suite | laptop, 4 cores, 256-bit | RELEASED |
| the fix, paced on C2's queue every 25 rounds | laptop, 4 cores, 256-bit | RELEASED |
| the fix, paced on C2's queue every round, to 10 | laptop, 4 cores, 256-bit | COMPILED twice, then RELEASED |
| the fix, paced on C2's queue every round, to 4 | laptop, 4 cores, 256-bit | RELEASED, three of three |

### 9.4 What remains, for the owner

1. **The warmed class's per-query cost at 54 to 100 entries** (9.3), measured the same
   afternoon and **not a regression**. The full benchmark on today's laptop, pinned as the files
   are: the 28 September engine itself reads 176 and 316 ms on the back-to-back rows at 54 and
   100 entries, Task 209's 187 and 346, the fix 184 and 336, all within 5% of one another, where
   the committed file of 28 September reads 128 and 240. The same engines agree with that file
   on every other row (once compiled 27 to 41 ms, steady state 47, 133, 255 ms), so those two
   rows moved with the day, not with the code: a machine effect on the back-to-back section
   alone, worth a line in `skills/benchmarking.md` when it is understood, and nothing the close
   waits on.
2. **The warm-up's thread.** 9.1's 200 to 600 ms now land on the first query's planning thread
   and the first task's thread, once per JVM. A row can move it back to a background thread once
   it can run before the query-path helpers without a race, or decide the cost is the right one
   to pay where it is.
3. **The hold rule's pair**, if the owner wants one for this change: the runner pair above is a
   verdict measurement, not a throughput pair; the steady-state rows say the fix has no
   steady-state cost against 28 September, and a pair on a runner would say the same for a
   4-core host.
