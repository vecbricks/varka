# VARKA-257: The slow mode of a kernel compiled after other kernels, as a function of N

## 1. Where this came from

Row 257 of `m7/PLAN.md`, from scope item 62 (`m10/SCOPE.md`) and `m7/READING.md` 11. Every JIT
verdict the project takes forks a fresh JVM per class - the deopt guard, the inlining-cliff probe,
the warm-up probe - because that is the only way to pin an outcome on one class. But a production
JVM runs many kernels, and the record has two kinds of slow kernel that only a history shows.
`VARKA-209.md` 12.3 and 12.4 read the 64-cheap-tail kernel, four loop methods all under the
call-site budget, slow in 4 of 80 benchmark instances (265.8 ns a row against 5.9 for the same
class in the same JVM), and in none of 140 fresh JVMs of the class alone. And `VARKA-295.md` 9.5 to
9.8 found warmed kernels compiled at tier 4 that still allocate one `int[16]` per loop iteration
(cause 4), because the shared Vector API templates carry traps left by earlier compiles: C2's
compile-wide trap sums (9.6, `VARKA-371.md`) at the preferred species, a per-bytecode trap at
`IntVector.add` at a narrower one (`VARKA-373.md`). The warm-up's own template prewarm
(`VarkaVectorTemplateWarmup`, 9.7) took the 512-bit releases to zero, and does nothing at a 128-bit
preferred species (`VARKA-373.md` 9, `VARKA-374.md`). Item 62 asks for a probe: N other kernels of
the corpus first, then the kernel under test, forked per N, read the way the cliff probe reads a
fork. Done when the probe exists with readings committed beside the cliff probe's, the slow mode's
frequency under history is a number, and the nightly runs one arm of it. The row adds that the
forks compare answers too.

## 2. The admission check, done

Run on 16 October 2026 with the probe of 3.1 as first written, before the plan was committed,
pinned to cores 12-17 of the laptop (AMD Ryzen AI 9 HX PRO 370, 512-bit preferred species,
OpenJDK 25.0.4.1) at a machine load of 27 to 28 from other agents' work, two JVMs of three cores
each, the warm-up's deadline 30 s, the logs under the worktree's `target/varka-kernel-history/`.

**The kernel under test is the 24-output case, not the 64.** The first smoke runs used the
64-tail class of `VARKA-209.md` 12.3. Warmed by the worker, C1 kept off it, at this load it took
45 and 60 s to reach its verdict (one compiled, one released at a 60 s deadline with every loop
at tier 4), 8 to 11 ns a row after, 1,280 bytes a call; at a 10 s deadline it was released with
three of its eight loop methods still without code. At that cost a sweep of ten forks per N is
hours, and the nightly could not carry it. The 24-tail case of the cliff guard (two groups, four
loop methods) warms in 0.8 to 2.0 s and runs at 2.5 to 3.7 ns a row with 0 bytes a call, so it
is the default (`--outputs`, the probe's fourth argument, keeps the 64). And the warm-up keeps
C1 off a warmed class, so VARKA-209's own slow mode, the loop past C1's limit, cannot occur on
this path; the rate cut stays as the reading of a loop C2 never reached.

**The trial, prewarm off, N = 0 and 16, three forks each:**

| reading | N = 0 | N = 16 |
|---|---|---|
| kernel under test: slow / forks | 0 / 3 | 0 / 3 |
| its rate, ns a row; bytes a call | 2.5 to 3.5; 0 | 3.4 to 3.7; 0 |
| history kernels released, positions 0 / 1-3 / 4-15 | 0 / 3 | 0 of 3, 0 of 9, 2 of 36 |

**The two releases are the history's, not their shapes'.** Both were in fork 0 (seed 20260903),
at positions 6 (an int kernel, one output) and 13 (a long kernel, two outputs). Each draw run
alone (N = 1 from its draw, `-Dvarka.history.from`) three times: 6 of 6 compiled, in 1.4 to 4.7
s. Fork 0's history replayed whole, twice with the prewarm off and twice on:

| fork 0, N = 16 | released, by position, with every loop method at tier 4 |
|---|---|
| prewarm off, run 1 | 6 |
| prewarm off, run 2 | 6, 8, 13 |
| prewarm on, run 1 | 13 |
| prewarm on, run 2 | 13, 14 |

Every released kernel had C2 code in use for every loop method: cause 4 of `VARKA-295.md` 9.5, a
compiled kernel still allocating, reproduced from a history of grammar kernels at the preferred
species. The prewarm removed position 6's release and not position 13's, a long-lane kernel,
which no record so far has: 9.7's "0 of 2,000" was read on the Spark fuzzer's compositions.

**What the check would have rejected.** Releases at no position past 0 with the prewarm off would
have said the grammar's kernels do not leave the templates' traps the Spark compositions did
(risk 1), and the probe would have had to draw the coverage compositions instead. It does not:
the design stands, with two changes from the first draft, both above - the 24-tail default, and
the history kernels' releases by position as the corpus-wide reading beside the fixed kernel's,
since the fixed kernel showed nothing in six forks where the history did.

## 3. The design

### 3.1 The probe, the launcher, the reader

**`VarkaKernelHistoryProbe`** (Java, catalyst's test tree, beside `VarkaInliningCliffProbe`), one
JVM per fork, arguments `<N> <seed> <seconds>`:

1. **The history.** N kernels of the IR fuzz grammar (`VarkaIrGrammar.drawShape` and
   `drawLongShape`, int and long lane in turn, draw `k` from `shapeRandom(seed, k)`), each emitted
   through `VarkaShapeCacheImpl` under `VarkaEmitOptions.DEFAULTS` with the warmed mark, and warmed
   by production's own worker (`VarkaKernelWarmup.start`, then `awaitIdle`), as
   `VarkaKernelWarmupProbe.warm` does. So the history is production's: the C1 exclusion directive,
   the 1,024-row copy, the allocation probe's verdict, and the Vector API template prewarm on the
   worker's first start unless `-Dvarka.warmup.vectorTemplates=false`. A draw the emitter refuses
   is skipped and the next draw takes its position. **One species per JVM**: no draw carries a
   lanes override (`VarkaMatrix.base` is not used, since a matrix property could set one), because
   two int species in one JVM box every later kernel for a reason of their own (`VARKA-209.md`
   13.2), which item 62 sets apart. The 128-bit reading is a JVM with `-XX:MaxVectorSize=16`.
2. **The kernel under test.** The cliff probe's cheap tails, `year(d) + k` for 64 outputs over one
   date, compiled by `VarkaExpressionCompiler` and emitted through the same cache under the
   defaults (so production's grouping, not the cliff probe's `predictGrouping=false`), warmed the
   same way, then run on null-free 1,024-row calls for `<seconds>`, the cliff probe's timed loop:
   the rate each second, the allocation per call over the last second. Then the code list's tier
   for each of its loop methods.
3. **The answers.** Every kernel, the history's and the one under test, is compared after its
   warm-up on the instance the JVM compiled, through `VarkaKernelChecks.runLoaded` (VARKA-274's
   entry point for a kept class), over its batch with every fifth row null and again with none,
   against `VarkaReference`. A guard's decline is allowed and reported; a wrong answer is printed
   and the process exits 1.

The slow mode, read two ways, both reported: **by rate**, the cliff reader's cut, the median of the
last three seconds over 40 ns a row (the loop past C1's limit of VARKA-198/209, 115 to 290 ns a
row against 1 to 7); and **by allocation**, over a cut of bytes per call set from the admission
trial's clean forks (cause 4 of VARKA-295, a box per loop iteration: 64 iterations of 80 bytes per
loop method per 1,024-row call at 16 lanes, 5,120 bytes, at a rate only a few ns a row slower).
And for the history's kernels, the warm-up's own verdict: RELEASED is a kernel still allocating at
the deadline, counted by its position in the history.

**`dev/varka_kernel_history.sh`** forks the probe per case (width, prewarm, N) and fork, the JVM
started directly from catalyst's exported test classpath, as `dev/varka_deopt_census.sh` does,
`--jvms` JVMs at once each pinned to `--cpus` cores, the warm-up's deadline at 10 s (the compiled
fuzz test's; production's 60 s only lengthens a released kernel's wait), the memory sanitizer off
since the JIT is what is measured. Fork `f` draws its history from seed `--seed + f`, so a case's
forks see different histories and replay by seed. **`dev/varka_kernel_history.py`** reads the logs:
per fork, per case the slow forks by rate and by allocation, the history kernels' releases by
position, and every wrong answer; `--guard` exits 1 on a wrong answer, an unfinished fork, or more
slow forks than `--max-slow`.

### 3.2 The nightly arm

One step, `history` (`--skip-history`), after the `deoptnull` step: one case, the host's width,
prewarm on (production), N = 16 (where VARKA-209's slow instance sat, after nine), a handful of
forks, under `--guard`. Its count of slow forks allowed is set from the measurement (section 9):
zero if the production arm shows none. It reuses catalyst's test build when the `deoptnull` step
built it. The block is self-contained: its summary line is printed after the shared summary loop,
as the platform step's is, so the shared step list is not edited (row 274b adds a step to the same
file on the same base).

### 3.3 What is deliberately unchanged

* The cliff probe and its reader (`VarkaInliningCliffProbe`, `dev/varka_inlining_cliff.*`): the
  new probe copies its timed loop and its cut rather than changing them.
* The warm-up, the template prewarm and the emitter: no product code changes. A fix for what the
  probe finds is its own row.
* The IR fuzz suite and `VarkaCompiledCheck` (rows 274 and 274b), and the Spark fuzz suites (row
  298): the probe draws from `VarkaIrGrammar` directly.

### 3.4 Registered op counts

None; no emitter change.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/src/test/java/.../codegen/varka/VarkaKernelHistoryProbe.java` | the probe |
| `dev/varka_kernel_history.sh` | the launcher |
| `dev/varka_kernel_history.py` | the reader |
| `sql/catalyst/benchmarks/VarkaKernelHistory-jdk25-probe.txt` | the committed readings |
| `dev/varka_nightly.sh` | the `history` step, added lines only |
| `sql/varka/skills/testing-and-debugging.md` | the calibration line |
| `sql/varka/plans/m7/PLAN.md` | row 257's line |

## 5. Tests, and what each is for

The probe is a measuring tool, like the cliff and warm-up probes, and has no suite of its own; its
checks are what it runs. What would make it lie, and what shows it does not:

* **The answer check compares something.** VARKA-274's planted wrong answer
  (`VarkaKernelChecks.plantWrongAnswer`) is a static flag of the check the probe calls; one fork run
  with it set must print `VARKA_HISTORY_WRONG` and exit 1, and the reader must fail under
  `--guard`. Done by hand once, recorded in section 9, not a committed test: setting it needs a
  probe argument nobody else should pass.
* **The history is the history.** Each fork prints one line per history kernel, its state and
  answers, so a fork that warmed nothing reads as N=0.
* **The slow cut is not a guess.** The allocation cut is set from the trial's clean and slow forks
  and stated with them.

## 6. The measurement

The full sweep, committed as `VarkaKernelHistory-jdk25-probe.txt`: N = 0, 4, 16, 64, ten forks per
case, at the host's width (512 bits here) and at 128 bits, prewarm on and off; pinned to cores
12-17 (shared with other agents, so the load is stated).

### 6.1 Predictions, registered before the run

1. **At 512 bits with the prewarm on, no slow fork at any N** (VARKA-295 9.8: 0 releases in 2,000
   compositions at the preferred species), and no history kernel released after position 0.
2. **At 512 bits with the prewarm off, the history kernels' release rate grows with position**,
   from about 0 at position 0 to several per cent by position 16 and beyond (9.6: 18, 10 and 39 of
   500 warm-ups; 9.7: 33 to 78), and the kernel under test is slow by allocation in some forks at
   N = 16 and 64 and in none at N = 0.
3. **At 128 bits, releases with the prewarm on and off alike**, near VARKA-373's 42 per 100
   compositions at the history's later positions, and the prewarm moving nothing.
4. **No wrong answer** in any fork.
5. **The nightly arm costs about 3 minutes**: six forks of N = 16, each about 16 kernels at the
   compiled test's 1 to 2 s plus 12 s timed plus 5 s of JVM start, two JVMs at once, at the
   calibration table's ratio for a JIT-bound step (VARKA-378, 0.9 to 1.3), so 2.5 to 4 minutes.

## 7. Risks

1. **The IR grammar's kernels do not pollute the templates the way the Spark fuzzer's compositions
   did**: then prediction 2 fails and the probe reads zero everywhere at 512 bits. The trial of
   section 2, prewarm off, is the check.
2. **A busy machine reads as history**: a compile late on shared cores releases a kernel at the
   deadline. The release counts at N = 0 (position 0) are the control.
3. **The kernel under test is a fixed shape**: its slow modes are the cliff's and cause 4's, not
   every kernel's. The history kernels' release counts by position are the corpus-wide reading.

## 8. Sequencing

1. The plan, with the admission trial's numbers.
2. The probe, the launcher and the reader.
3. The sweep and its committed readings, the nightly step, the calibration line, section 9.

## 9. Outcome

### 9.1 Corrections to 3.1, 4 and 5, after the build and the review

3.1 was written before the admission trial and describes the first draft. As built (section 2 has
why): the probe takes a fourth argument, the cheap-tail count of the kernel under test, 24 by
default; the launcher's warm-up deadline is 30 s, not 10. The self-review (`/code-review high`)
then changed five things in the probe and reader: a draw whose shape repeats an earlier draw's
(the cache key holds no literal values, so the cache hands back the kernel already warmed) is
skipped instead of ending the fork; only `VarkaEmitDeclined` skips a draw, any other emitter
failure ends the fork and reads as unfinished; the timed calls' statuses are kept and a fork
whose timed calls declined reads as declined, not fast; a failure of the harness rather than the
check is `VARKA_HISTORY_ERROR`, exit 2, apart from a wrong answer; the reader keys a case on the
fork's file name, so a fork that died before its first line stays in its case, and reads each
fork's exit status. Section 5's planted answer is a property, not a hand edit:
`-Dvarka.history.plantWrongAnswer=true` sets VARKA-274's plant; one fork with it printed two
wrong answers of three kernels (the third's row 0 is null in its batch, where the plant has no
value to change) and the reader failed under `--guard`. The history counts are split by lane,
since the lanes alternate and position 0, the control, is always an int kernel.
Two files beyond section 4's table: the lesson in `sql/varka/skills/the-jit.md` and its line in
the generated `SKILLS.md`.

### 9.2 The readings

`sql/catalyst/benchmarks/VarkaKernelHistory-jdk25-probe.txt`, 16 October 2026, cores 12-17 at
machine loads of 14 to 30 from other agents' work, two JVMs of three cores each. 52 forks of the
sweep, 1,140 kernels warmed and compared, 0 wrong answers.

**The kernel under test is never slow.** 0 of 36 forks at the host's width (N = 0, 16 and 64,
prewarm on and off, six forks each) and 0 of 16 at 128 bits (N = 0 and 16), every one compiled
by the warm-up with all four loop methods at tier 4, 0 bytes a call, 1.0 to 2.9 ns a row at the
host's width and 2.6 to 5.7 at 128 bits. For this kernel the slow mode's frequency as a function
of N is zero at every N measured, against the 4 of 80 benchmark instances of `VARKA-209.md`
12.4. Those were the 64-tail class with C1 on in a benchmark JVM, a mode the warm-up's C1
exclusion takes away.

**The history's own kernels are where the slow mode is**, and it grows with the history.
Released at the 30 s deadline still allocating, of those warmed, at the host's width:

| position in the history | int, prewarm off | int, prewarm on | long, prewarm off | long, prewarm on |
|---|---|---|---|---|
| 0 (the control) | 0 of 12 | 0 of 12 | - | - |
| 1 to 3 | 4 of 12 | 0 of 12 | 0 of 24 | 0 of 24 |
| 4 to 15 | 15 of 72 | 0 of 72 | 11 of 72 | 7 of 72 |
| 16 to 63 | 40 of 144 | 15 of 144 | 46 of 144 | 43 of 144 |

Every long-lane release and 54 of the 74 int-lane ones had C2 code in use for every loop method:
cause 4, compiled and still allocating. The other 20 had a loop method with no code in use, a
compile that never came within 30 s or a deoptimization cycle (`VARKA-326.md`), which a code list
cannot tell apart. The releases are the history's: the two first released draws ran alone 6 of 6
compiled, the long lane's first draw alone compiled at the host's width in 6 of 6 seeds, and
position 0 never released.

**So the template prewarm works for the int lane and not for the long lane.** With it, no int
kernel released before position 16 (0 of 84, against 19 of 84 without), and the int rate past 16
fell from 27.8 to 10.4 per cent. The long lane's did not move: 7 against 11 of 72 at positions 4
to 15 and 43 against 46 of 144 past 16, 29.9 per cent with the prewarm. VARKA-295 9.8's "0 of
2,000" was read on the Spark fuzzer's compositions, whose kernels are mostly int-lane; this is
the first reading of long-lane kernels after a history, and it says the prewarm, which calls the
`LongVector` templates too, does not keep their profiles clean against what the grammar's long
kernels do to them.

**At 128 bits the long lane releases alone.** 31 of 32 long kernels released at both prewarm
settings, and the long lane's first draw alone was released at 128 bits in 6 of 6 seeds, every
loop at tier 4: that is the shape at that width, `VARKA-374.md`'s case (no two-lane masks on
x86), not a history. The int lane at 128 bits: 10 of 28 released past position 0 without the
prewarm, 4 of 28 with it, 0 of 8 at position 0.

### 9.3 The predictions of 6.1, scored

1. **At 512 bits with the prewarm on, no slow fork at any N: holds** (0 of 18). **No history
   kernel released after position 0: wrong.** 15 of 144 int kernels past position 16 and 50 of
   216 long kernels past position 3 were released.
2. **With the prewarm off the history's release rate grows with position: holds** (int 0, 4 of
   12, 15 of 72, 40 of 144; long 0 of 24, 11 of 72, 46 of 144). **The kernel under test slow by
   allocation at N = 16 and 64: wrong**, 0 of 12.
3. **At 128 bits releases with the prewarm on and off alike, near 42 per 100: half right.** The
   long lane releases alike (31 of 32 in each arm), but alone, so it is not history; the int lane
   is 10 against 4 of 28, so the prewarm does move it, which `VARKA-373.md` 9 did not see on the
   Spark compositions at a 128-bit preferred species.
4. **No wrong answer: holds**, 0 in 1,140 kernels of the sweep, 102 of the nightly arm's run and
   54 of the trial.
5. **The nightly arm about 3 minutes (2.5 to 4): holds**, 3 min 52 s for six forks on three
   pinned cores at a load of 14, a ratio of 1.3 to the 3-minute point estimate.

### 9.4 The nightly step

`history` (`--skip-history`), after the `deoptnull` step: `dev/varka_kernel_history.sh --after 16
--forks 6 --guard`, the host's width, prewarm on. It fails on a wrong answer, a harness error, an
unfinished or declined fork, or any slow fork of the kernel under test (`--max-slow 0`, since 0
of 52 forks were slow); the history's releases are printed and not failed, since at N = 16 with
the prewarm on they are a measured 7 of 72 long kernels and the step is a reader of them, not a
ratchet. **It adds about 4 minutes to the nightly** (3 min 52 s here; on an idle machine it can
use the default cores 0 to 5 and be faster), plus catalyst's test build when the `deoptnull` step
did not run.

### 9.5 What this leaves

* **The long lane's releases after a history at the preferred species**, 29.9 per cent past
  position 16 with the prewarm on, untouched by it. Not this row's to fix (no product code
  changes here); it needs the classification VARKA-373 2 did for the int lane at four lanes
  (which template sites hold the traps, from `-XX:+LogCompilation` on a replayed history, which
  `-Dvarka.history.from` and the seed make exact), then a decision. Reported to the owner as a
  candidate row.
* **Which methods of a slow kernel are slow, under a C2 compile that still allocates**: the code
  list says every loop is C2 code; which loop keeps the box needs the compile log or JFR's
  allocation samples, as VARKA-373 took them.
* **The 64-tail kernel of VARKA-209 under history**, `--outputs 64`, priced at 45 to 60 s a fork
  on loaded cores and not run here.
