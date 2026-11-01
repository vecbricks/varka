# VARKA-505: AVX2, 128-bit and Arm as a fixed part of every option matrix

## 0. What exists now (admission check)

From master at `b9c587374b6`, and from the MaxGekk/spark Actions runs named below, read on 1
November 2026 with `gh api` GETs. Nothing was dispatched.

1. **What the pool handed out.** Four recent option-matrix runs had 40 bin jobs each. Their
   "Record the CPU and the vector width" step (`.github/actions/record-runner-platform`) printed:

   | run | branch | AVX2 bins | AVX-512 bins |
   | :-- | :-- | --: | --: |
   | 41271435357 | `varka-348-census` | 25 | 15 |
   | 41268327890 | `varka-332-baseline` | 19 | 21 |
   | 41261265107 | `varka-359-baseline-results` | 20 | 20 |
   | 41239405318 | `varka-339-ntz-arith` | 25 | 15 |

   Of the 160 bins, 82 ran on an EPYC 7763 and 7 on an EPYC 9V74, all at `UseAVX=2
   MaxVectorSize=32`. The other 71 ran at `UseAVX=3 MaxVectorSize=64`: EPYC 9V45 (27), EPYC 9V74
   (25), Xeon Platinum 8573C (10), Xeon Platinum 8370C (6) and Xeon 6973P-C or 6975P-C (3). So
   every run mixes the two, and which (configuration, suite) bin gets AVX2 is random. An
   EPYC 9V74 appears on both sides, so the CPU model alone does not tell a reader which
   instruction set a bin ran under. The `JVM:` line does.
2. **No bin is forced.** `varka-option-matrix.yml` runs every bin on `ubuntu-latest`
   (`:254-262`, `max-parallel: 30`) and passes no vector flag. Only two Varka workflows force a
   flag: `varka-fuzz.yml:317` runs one `-XX:MaxVectorSize=16` slice, and the canary and width
   audit run on `ubuntu-24.04-arm` but run no test suite.
3. **What runs on Arm.** The Build's "Varka engine" job runs the engine module's own suites on
   x86_64 and aarch64 (`build_and_test.yml:1243-1267`). No catalyst or sql Varka suite runs on
   Arm.
4. **The one forced suite.** `VarkaAvx2SelectSuite` (row 491's regressions for row 396) forks
   under `@VarkaSuite(ownJvm = true, jvmFlags = {"-XX:+IgnoreUnrecognizedVMOptions",
   "-XX:UseAVX=2"})` (`VarkaAvx2SelectSuite.java:49`). It is the only suite that pins the
   instruction set.
5. **The runner has the knobs.** `dev/varka_matrix.sh` takes `--defaults` (`:138`), `--split N`
   (`:139`) and `--jvm-arg` (`:140`). The bins job calls it with `--runs-only` per bin
   (`varka-option-matrix.yml:361`).
6. **The branch the row names exists.** `varka-503-two-lane-decline` is a local branch.
   `pr-503-review` is beside it.
7. **The slot budget has moved.** The fork runs 40 jobs at once. A matrix takes up to 30 bins
   at a time (`max-parallel: 30`), plus its build and report jobs. The workflow's comment
   (`:20-21`) says "about a hundred jobs" a run. Matrices are run one at a time by the
   owner's practice, not by anything the workflow enforces, so a second run's bins wait.

### What differs from the row text

- The row's budget ("two runs in flight within the fork's 40 jobs, so at most 8 jobs for the axis
  beside the 12 shards") predates the fan-out to bins (rows 507 and 513). There are no 12 shards:
  a run is about a hundred bin jobs through 30 slots, and two runs never share the 40 slots
  (item 7). The axis's budget has to be restated in bins and wall minutes (section 7, question
  1).
- "No test suite runs on Arm" is true of the catalyst and sql suites only. The engine module's
  suites run there (item 3).
- The pool's AVX2 share is not small: in the four runs above it is 89 of 160 bins. Today any
  configuration gets AVX2 coverage about half the time, but no single run guarantees it for a
  given suite under the defaults.

## 1. Goal and non-goal

**Goal.** Every dispatch of the option matrix also runs the Varka suites at the defaults under
three fixed platforms: AVX2 (`-XX:UseAVX=2 -XX:MaxVectorSize=32`), the 128-bit species
(`-XX:MaxVectorSize=16`) and Arm (`ubuntu-24.04-arm`, defaults). A defect of row 396's class,
which C2 shows only without mask registers, then fails a pull request's matrix on its first
run, not on a lucky night.

**Non-goal.** No option configuration crossed with the axis: only the defaults run under each
platform. No change to the PR Build. No new skip rules beyond what the axis's first run finds.

## 2. Design

### 2.1 Three platform configurations in the planner

`dev/varka_matrix_bins.py` gains three pseudo-configurations, `isa=avx2`, `isa=w128` and
`isa=arm`, planned beside the option configurations. Each one carries:

- its JVM flags, which the bin passes through `dev/varka_matrix.sh --defaults --jvm-arg ...`;
- its runner: `ubuntu-latest` for the first two, `ubuntu-24.04-arm` for the third. The bins
  matrix gains a `runner` field, read by `runs-on: ${{ matrix.runner }}`;
- a full reach. A platform changes what C2 does with the same bytes, and for `w128` also the
  lane count, so the reach record's "this configuration moves nothing this test emitted" is no
  reason to skip. The three go in `VarkaTestReach.FULL` with that reason, as `AGENTS.md` asks
  for an option read outside the compiler. `w128` also emits different bytes, so it is full on
  both counts.

### 2.2 The first check: the axis catches the class without a test for it

Before the axis is the default, on a scratch branch:

1. revert row 491's guard and skip `VarkaAvx2SelectSuite`;
2. dispatch the matrix with only the axis's configurations;
3. expect `isa=avx2` to fail in the broad suites (the compiled-history test of
   `VarkaIrFuzzSuite`, which found row 396, among them), while `isa=w128` on an AVX-512 host
   passes. The record-runner-platform line of each failing bin shows `UseAVX=2`.

The run ids and the failing tests go in the outcome. If `isa=avx2` passes on that branch, the
axis does not catch the class: the row stops and reports, since the axis's value rests on this
check.

### 2.3 Skips

A test the axis breaks by design gets a line in `sql/varka/matrix/skips.tsv` under its platform
configuration, with its reason, as every skip does. Under `w128`, the two-lane condition decline
of `varka-503-two-lane-decline` decides what fuses at two long lanes. Its decline lines belong
to row 503, which this row does not wait on (question 3).

### 2.4 The record

The report job already prints "Bins by CPU" (`varka-option-matrix.yml:435-438`). It gains one
line per platform configuration: its runner's CPU and `JVM:` flags. A forced `UseAVX=2` on a
host that is already AVX2 then reads as a no-op rather than as coverage.

## 3. Tests and proofs

1. `dev/varka_matrix_bins.py`'s unit tests: a plan with the three platform configurations gives
   each its flags and runner, and gives them full reach even when the reach record says nothing
   moved.
2. The scratch-branch check of 2.2, recorded with run ids.
3. One green dispatch on master with the axis, its job count and wall time recorded.
4. The axis's own failure path: `isa=arm` on a host where the JDK lacks `jdk.incubator.vector`
   fails the bin, which is never a silent skip (**[I]**: the plan did not check the Arm image's
   JDK set-up step in the bins job).

## 4. Predictions and costs, registered before the build

- P1: each platform configuration runs every Varka test once at the defaults. Its bin-minutes are
  about one defaults pass of the catalyst and sql Varka suites (**[I]**: the plan did not find a
  committed figure for one full defaults pass. `m8/results/VARKA-matrix-bins-1030.txt` gives a
  reach-filtered run's 106 bins and a no-reach run's 126, not the defaults' share).
- P2: the axis adds three times P1 to every dispatch. Through 30 slots it adds wall time unless
  the axis takes slots of its own (question 1).
- P3: the scratch-branch check fails under `isa=avx2` in at least one broad suite.
- P4: on master the axis passes once `skips.tsv` holds what `w128` declines by design.

## 5. Order, files and size

| File | Change |
|---|---|
| `dev/varka_matrix_bins.py` | the three platform configurations, their runner and flags, unit tests |
| `.github/workflows/varka-option-matrix.yml` | `runs-on` from the bin's runner, the JVM-arg pass-through, the platform report line, the job-count comment |
| `sql/catalyst/src/test/java/.../VarkaTestReach.java` | the three in `FULL`, with the reason |
| `sql/varka/matrix/skips.tsv` | what the first run finds |
| `sql/varka/skills/testing-and-debugging.md` | how to read a platform bin |

Order: planner and workflow first, then the scratch-branch check (2.2), then master. The PR's
own matrix dispatch is its first proof that the axis runs.

Size: medium, as the row says.

## 6. Out of this row

- Crossing the axis with the option configurations. That would multiply the matrix by four.
- SVE hosts. The pool's `ubuntu-24.04-arm` is NEON.
- Pinning the PR Build to one instruction set.
- Row 503's two-lane decline itself.

## 7. Questions for the owner

1. **The budget.** The row's "8 jobs beside the 12 shards" no longer describes the matrix (0.7).
   Options:
   - (a) the axis shares the 30 slots and lengthens the wall by about its bin-minutes over 30;
   - (b) the axis gets its own slots, up to the fork's 40 less the matrix's 30 and its build and
     report jobs, which is about 8. Rows 522 and 531 then cannot run beside a PR matrix;
   - (c) the axis runs only on dispatches whose branch touches the emitter or the IR.

   Recommended: (b), since (c) would miss row 396's class on a change elsewhere.
2. **Arm on every dispatch.** An Arm bin needs its own classpath download and JDK. Should Arm be
   on every dispatch, or on the weekly run only? Recommended: every dispatch, since the 128-bit
   NEON path is where the narrow lanes' part form (row 479) runs natively.
3. **Row 503's decline under `w128`.** Recommended: list its two-lane declines in `skips.tsv`
   under `isa=w128` until row 503 merges, rather than wait on it.

## 8. Done when

- every dispatch of `varka-option-matrix.yml` runs the defaults under `isa=avx2`, `isa=w128` and
  `isa=arm`, with each platform bin's CPU and flags in the report;
- the scratch-branch check is recorded with its run ids, `isa=avx2` failing without row 491's
  guard;
- a dispatch on master passes with the axis;
- the workflow's comment states the run's job count and how it fits the fork's 40 slots under
  the owner's answer to question 1.

## 9. Open points for the lead

1. The row's budget sentence needs the owner's restatement (question 1) before the build. The
   rest of the design does not depend on which option is picked.
2. Seven of the 89 AVX2 bins were EPYC 9V74s reporting `UseAVX=2`, a CPU that elsewhere in the
   same runs reports `UseAVX=3`. So a CPU model in a results file is not evidence of the
   instruction set. Worth a line in `sql/varka/skills/benchmarking.md` whatever this row decides.
3. Whether `varka-503-two-lane-decline` is still live work, or parked, decides how question 3 is
   worded. The plan did not open it.

## 10. Outcome
