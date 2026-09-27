# Task 221: The warm-up's verdict is relative, and admits a kernel that still boxes

*Scoped 27 September 2026 (milestone 6 row 221, from the fork CI of #458);
opened, investigated and fixed 27 September 2026.*

## 1. Where this came from

On the fork CI of #458 three of `VarkaKernelWarmupSuite`'s tests - "a warm-up
claimed by a batch with no nulls, some nulls, only nulls compiles both of the
kernel's drivers" - met a kernel the warm-up had called `COMPILED` that then
allocated 396032 bytes over the sixteen 1024-row calls the tests make, where
a compiled call allocates only its memory segments (the tests' bound is
20480). The same suite failed once in a full local run that morning and
passed alone and on its rerun. Row 221 read the verdict as relative - a
four-fold drop from the first probe - and guessed the mechanism: a C2 body
compiled before the vector classes it intrinsifies were loaded, the scalar
fallback. Until the task landed the three tests carried `testRetry` (#459).

Both parts of the row's reading turn out to be wrong, and section 2 says
how.

## 2. The admission check, done

Checked on master `4ce07f33462`, 27 September 2026, on the laptop, with the
suite run as plain JVMs on two cores (2 and 3) that two busy loops held -
the starvation `sql/varka/skills/the-jit.md` records as reproducing a
four-core runner's compile delays. The readings are committed in
`VarkaKernelWarmupProbe-jdk25-probe.txt`, and this plan quotes them.

**2.1 The failure reproduces.** Twelve runs with `-XX:+PrintCompilation` and
`-Xlog:deoptimization=debug`: three failed, each in "some nulls", each with
`308608 was not less than or equal to 20480` and the outcome
`firstProbeBytes=736960, lastProbeBytes=14592` - the same bytes in every
failing run, so the failing mode is deterministic once it happens. Twenty
more runs under JFR and under `PrintInlining` failed two and one. No run
showed a deoptimization of any of the kernel's methods.

**2.2 The verdict is not only relative.** A probe block is clean when it is
under an absolute allowance - the sampler's fixed allowance, one byte per
row, and `SEGMENT_BYTES_PER_COLUMN` per column per call - *and* a quarter of
the first block. The failing verdicts met both: 14592 bytes over sixteen
calls of 32 to 63 rows is about 900 bytes a call, under the allowance for a
kernel of one input and three outputs.

**2.3 Who allocates.** JFR's allocation samples on the test thread, under
the test's own calls (`allocationOfCalls`), in the failing run: 6140.6 kB in
`loopMasked1`, 1022.3 kB in `loopDense1`, next to nothing in the other
methods; the allocating frames are the Vector API's Java fallbacks,
`ScopedMemoryAccess.loadFromMemorySegmentScopedInternal` (3584.1 kB) and
`IntVector.lanewiseTemplate` (2142.9 kB), allocating `int[]` and
`Int512Vector`. The kernel has two groups - `add_months` and `last_day` over
one date in group 0, `date_add` in group 1 - and it is the light group's
loop, 152 bytes and four lane operations, that boxes.

**2.4 Not a bad compile: a late one.** `PrintInlining` on every C2 compile of
`loopMasked1`, in the failing run and a passing one: every vector call
inlined as an intrinsic, no inlining failure of any kind. So a compiled
`loopMasked1` cannot box, and the row's scalar-fallback reading is refuted.
What the compile log shows instead is the order: on the starved cores C2
compiles the kernel's methods one at a time, about a hundred milliseconds
each - `loopDense0`, `loopMasked0`, then `loopMasked1`, then `loopDense1` -
per class, in every attempt.

**The mechanism.** The verdict reads the whole kernel's allocation. Once the
heavy group's loop compiles, the kernel's allocation falls far below the
first probe's, and on the warm-up's short calls the light group's
still-interpreted loop boxes about 900 bytes a call, which fits under the
per-column allowance. The verdict says `COMPILED` a compile or so before
`loopMasked1` is compiled; the test's first 1024-row calls then run that
loop interpreted, at about nineteen bytes a row (308608 over sixteen calls).
In production the same race puts a shape's first real batches through an
interpreted loop the warm-up said was compiled, silently.

What the check would have rejected: a fix aimed at the scalar fallback (a
recompile, a class-loading order), which 2.4 shows is not happening; and a
tighter drop factor, which cannot tell nine hundred bytes of boxing from nine
hundred bytes of segments on a short call.

## 3. The design

### 3.1 Long probe calls

Boxing grows with the rows a call runs; the segments, which the allowance
exists to excuse, grow with the calls. So the probe block runs long calls -
the whole snapshot, `PROBE_ROWS` = 1024 rows each, four calls alternating
the dense and masked drivers - while the spin between probes keeps its short
slices, which are what build the profile C2 compiles from and reach the
thresholds quickly. Over a 1024-row call an interpreted loop boxes some
twenty thousand bytes against an allowance of about a thousand per call, so
the verdict waits until every loop method the probe reaches is compiled.

The masked probe calls pass each input's validity and a null count of one,
whatever the input holds: the count's only other effect is the driver's
all-null shortcut, which returns before any loop runs, and a probe that runs
no masked loop cannot see one interpreted. The first version passed an
all-null input's full count and failed "only nulls" in every run, the verdict
coming before the masked loops had run at all (section 9).

### 3.2 What is deliberately unchanged

The allowance and its terms, the drop, the clean-probe count, the spin, the
deadline, the snapshot, the C1 exclusion, and the kernel's code. The
warm-up's `Outcome` keeps its fields; `calls` counts probe calls as before.

### 3.3 Registered op counts

None: no emitted code changes.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/PLAN_TASK_221.md` | this plan |
| `sql/catalyst/benchmarks/VarkaKernelWarmupProbe-jdk25-probe.txt` | the check's and the measurement's readings |
| `.../codegen/varka/VarkaKernelWarmup.java` | the probe's long calls and their buffers; the class doc |
| `.../codegen/varka/VarkaKernelWarmupSuite.scala` | the `testRetry` removed, the comment saying what the three tests pin |
| `sql/varka/skills/the-jit.md` | the lesson |
| `sql/varka/plans/PLAN_MILESTONE_6.md` | row 221 |

## 5. Tests, and what each is for

1. **The three drivers tests, without the retry**, are the regression test:
   their absolute bound over 1024-row calls is what caught the race, and a
   verdict that came early again would fail them on a busy machine.
2. **The starvation reproduction**, thirty runs, is the check that the race
   is gone: it is not a suite, since it needs cores held busy, and its
   script is in section 9.
3. The rest of `VarkaKernelWarmupSuite` and every Varka suite of
   `sql/catalyst`.

## 6. The measurement

The warm-up's duration, from its own log line ("is compiled after a warm-up
of N ms"), over the starved runs before and after: the fix waits for a
compile the old verdict did not, so it should cost about one C2 compile.

### 6.1 Predictions, registered before the run

1. Thirty starved runs of the suite without the retry: no failure, where
   the old verdict failed one run in seven even with it.
2. The median warm-up grows by about one C2 compile on the starved cores, a
   hundred milliseconds, and no more than a quarter.

## 7. Risks

1. **A probe call reaches a branch the short calls never profiled**, and C2
   deoptimizes on it. The deoptimization log of 2.1 is the check; a probe
   over the same snapshot as the short calls reaches the same values.
2. **A long probe of an uncompiled wide kernel is slow at interpreted speed.**
   The first probe runs four interpreted calls of 1024 rows; the warm-up's
   deadline of sixty seconds bounds it.

## 8. Sequencing

One commit: the probe, the retry removed, the plan with the check and the
measurement, the lesson and the row.

## 9. Outcome

*Written 27 September 2026, after the measurement.*

The warm-up waits for every loop method its probe reaches, and the three
tests pass without a retry on the machine and the load that failed them.

1. **Held.** Thirty runs of `VarkaKernelWarmupSuite` on the two starved
   cores, the retry removed: thirty passed. The same setup before the fix
   failed five runs of thirty-five, retry and all.
2. **Held.** The warm-up's verdicts in those runs, read from the log: before
   the fix 109 verdicts with a median of 544 ms (p90 970, max 1318); after
   it 120 verdicts with a median of 636.5 ms (p90 1086, max 1265). About
   ninety milliseconds, one compile of the light loop, and the maximum
   slightly down.

What moved that the plan did not list: the first version of the fix failed
"only nulls" in all thirty runs. Its masked probe calls passed an all-null
input's full null count, the driver took its all-null shortcut, no masked
loop ran in any probe, and the verdict came with the masked loops never
compiled - 15417856 bytes over the test's sixteen calls. A masked probe
call passes a count of one (3.1).

The reproduction, for the next time the suite is suspected:

    taskset -c 2 bash -c 'while :; do :; done' &   # and the same on core 3
    taskset -c 2,3 java ... org.scalatest.tools.Runner -oW \
      -s org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaKernelWarmupSuite

What the task leaves: nothing on this row. The verdict still reads the
kernel as a whole, which is right now that the probe's calls are long enough
for every loop to show; a per-method verdict would need the compiler's own
state, which production cannot ask for.
