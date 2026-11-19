# VARKA-570: an option-matrix bin far past its plan stops itself and is rerun alone

## 1. Where this came from

Row 570 of `PLAN.md`, found on 5 November 2026 on #1348's option matrix: bin b023 ran 43 minutes
against a plan whose longest bin was 8, the other bins finished within 15, and the run had to be
cancelled. The cancel took minutes, a rerun of the failed bin was refused until the run had
completed, and the rerun then met a 502 from GitHub. Nothing says why b023 was slow, because a
cancelled job's log is not kept. It is the second such bin: `results/VARKA-530-ledger.txt` records
run 40942811546's b021 on an AMD EPYC 9V74, 91 minutes against the attempt's median bin of 8,
cancelled at its time limit and passed on attempt 2.

## 2. The admission check, done

* **The problem holds on master.** `.github/workflows/varka-option-matrix.yml` gives a bin job
  `timeout-minutes: 90` and nothing shorter. No step reads the bin's plan: the plan's `seconds`
  (the bin's longest lane, `dev/varka_matrix_bins.py` `plan`) reaches the job as
  `matrix.seconds` and is never used. No file under `.github` or `dev` handles a straggler. A slow
  runner therefore holds its run for up to 90 minutes, and while it does, `gh run rerun --failed`
  refuses the run.
* **How a job can see its own overrun.** A job keeps the runner it was given, and moving it
  needs a new attempt. Inside the job, the step "Run this bin's units" can still compare its own
  elapsed time with `matrix.seconds`. If it stops its units at a deadline and exits with a failure,
  it ends before the timeout. The upload step after it runs `if: always()`, so the bin's
  `platform.txt`, unit logs and partial JUnit are kept. That is the record a cancel loses.
* **How often, and where the deadline can sit.** Bin job times from the Actions jobs API
  (`results/VARKA-570-bin-times.txt`), for 13 completed matrix runs on MaxGekk/spark between 11
  and 19 November 2026 (42995163793 to 44485292430; seven more runs of the window did not answer
  the API), 2,042 bin jobs of every attempt in all: the
  median bin of a run took 6.9 to 8.2 minutes and the longest 15.4 to 24.5. The two longest,
  21.0 (run 43171990888, b092) and 24.5 (run 44141461002, b094), both passed. No job of the
  window ran past 25 minutes. A bin is packed to the planner's 480 s target, so three times the
  plan of a full bin is 24 minutes, and a job's time includes 1 to 3 minutes of setup that the
  units step does not. So a 24-minute floor on the deadline would have stopped none of the 2,042,
  but only just. Step 1 confirms this from the units step's own times before the number is
  fixed.
* **What the check would have rejected.** A deadline on the job's time rather than the units
  step's. A slow setup step (VARKA-513's 16-minute Java install) is retried by the setup steps'
  own timeouts, and counting it would stop a bin whose tests were on time.

## 3. The design

### 3.1 The deadline inside the units step

The units step starts a watchdog beside its units. The deadline is three times the bin's planned
seconds, floored at three times the planner's 480 s target, so 24 minutes for most bins. At the
deadline the watchdog:

* writes `target/varka-units/<bin>/<bin>-straggler/straggler.txt`, with the plan, the elapsed
  time, the runner's CPU line from `platform.txt`, and each unit's state (finished with its
  `seconds`, or running);
* stops the running units by the process group each one was started in (`setsid`, PID
  recorded at start), with TERM and then KILL after 30 s, never by pattern;
* prints `::error title=Straggler::<bin> ran <m> min against a plan of <p> min on <CPU>; stopped`
  and exits. After its `wait`, the step's main script finds `straggler.txt` and exits 3 instead
  of the 1 a killed unit's exit file would give, so the job's failure says straggler.

The upload step adds `*-straggler/straggler.txt` and sets `overwrite: true`, so that a rerun's
upload replaces the artifact of the attempt it reruns. `timeout-minutes: 90` stays as the last
stop for a step that hangs outside the units.

### 3.2 The report names stragglers

The report job's summary gets a "Stragglers" block listing each `straggler.txt`. A straggler's
configurations are named there as not run, rather than counted among the failures. The CI
ledger's "Slow" class (`dev/varka_ci_ledger.py`) reads the same file in place of a cancelled
job's guesswork.

### 3.3 The rerun, inside CI

`dev/varka_matrix_stragglers.py decide RUN` reads the run's jobs and their check-run annotations
from the API, as `dev/varka_bench_cpu_retry.py` does for a CPU mismatch (VARKA-591). It answers
`rerun` only when the run has completed, every failed job is a bin whose units step failed with
a `Straggler` annotation, and the run is at attempt 1. It answers `stop` otherwise and says why.

The rerun cannot be started from inside the run, since a run in progress cannot be rerun. So
`.github/workflows/varka-matrix-rerun.yml` is started by `workflow_run` when a run of "Varka
option matrix" completes with a failure, as `varka-bench-cpu-retry.yml` is for "Run benchmarks".
`workflow_run` reads the workflow from the default branch, and both MaxGekk/spark's and
vecbricks/varka's default branch is `master`, which carries Varka's workflows
(`varka-fork-sync.yml` keeps the fork's at Varka's master; VARKA-591.md 3 still says the fork's
is Apache Spark's, which no longer holds). Its job holds `actions: write` (the rerun) and
`checks: read` (the annotations), as the CPU retry's does, asks `decide`, and runs
`gh run rerun <run> --failed`. That reruns only the failed bins and the report job that depends
on them. A `workflow_dispatch` trigger with a `run-id` input covers a run that completed before
the workflow merged. One rerun at most: a bin slow twice is a finding, not luck. The laptop's
`~/varka-ops/rerun-matrix-artifacts.sh` can call the same `decide`.

For `decide` to see only stragglers, the report job must not fail because of them. Its merge
step leaves out the unit directories of a bin with a `straggler.txt`, and the summary names that
bin's configurations as not run, so `varka_matrix.sh --report-only` judges the completed bins
alone. The rerun's report then merges every bin.

Alternatives considered: the report job dispatching the rerun workflow (rejected once
`workflow_run` was found to work on both repositories: it needs the same permissions and a watcher
that waits for the run to complete); a laptop script only (rejected: it stops when the laptop
sleeps, the reason VARKA-591 moved the CPU retry into CI); and stopping a bin when its first unit
runs slow (rejected: a unit's plan is not in the bin's input, and the bin's longest lane is what
the planner bounds).

### 3.4 What is deliberately unchanged

The planner and its weights (rows 507, 508, 533 and 617). The build and report jobs' timeouts.
The setup steps' retries (VARKA-513).

## 4. Files

| file | what |
|---|---|
| `.github/workflows/varka-option-matrix.yml` | watchdog, upload, summary, merge |
| `.github/workflows/varka-matrix-rerun.yml` | `workflow_run`, `actions: write`, the rerun |
| `dev/varka_matrix_stragglers.py` | `history`, `decide`, `--selftest` |
| `dev/varka_ci_ledger.py` | the "Slow" class from `straggler.txt` |
| `sql/varka/skills/testing-and-debugging.md` | a section: what a straggler line means |

## 5. Tests, and what each is for

* `dev/varka_matrix_stragglers.py --selftest`: the decisions over recorded run shapes. A run with
  one straggler gives `rerun`. A straggler beside a test failure gives `stop`, since the test
  failure is not the runner's, and so does a failed report job beside a straggler. Attempt 2
  gives `stop`. A cancelled run gives `stop`. The step
  and annotation names it keys on are read from the workflow file, so a rename fails the test.
* The watchdog's shell function, run locally by the selftest with a 5 s deadline over two
  `sleep` units: the marker file, the exit 3, and no process left in either group.
* `actionlint` over both workflow files.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. Step 1's history over the 13 runs of section 2: no green bin's units step passes 24 minutes.
   b094 of run 44141461002 is the closest, 21 to 23 minutes. If one passes, the floor rises to
   the next multiple of 480 s above it, and the plan says so.
2. The planted straggler of step 5 is stopped within 1 minute of its deadline. The run completes
   within 5 minutes after that, and the rerun starts within 5 minutes of the run's completion.
3. The watchdog's cost on a bin that is on time is under 1 s, one `sleep` loop per bin.

## 7. Risks

1. A bin planned short but slow for a real reason (a configuration that slows a suite) is stopped
   and rerun once, then fails again. The second failure is the signal, and the straggler line
   names the units still running, so the cause is readable either way.
2. `overwrite: true` over a previous attempt's artifact. If GitHub keeps the first attempt's
   artifact as well, the report's `merge-multiple` sees two copies of one bin. Step 5's rerun
   shows which; the fallback is an artifact name carrying `github.run_attempt`.
3. The automatic rerun spends fork slots without a person's word. It reruns only work that the
   dispatch had already asked for, once. Question 1 is about this.

## 8. Sequencing

One pull request, about 400 counted lines.

1. `dev/varka_matrix_stragglers.py history` over the runs of section 2, read from the units
   step's own times in the jobs API. Done when its table is in this file and prediction 1 is
   scored.
2. The watchdog and the upload. Done when `dev/varka_matrix_stragglers.py --selftest` and
   `actionlint .github/workflows/varka-option-matrix.yml` pass.
3. The report block and the ledger class. Done when `python3 dev/varka_ci_ledger.py --self-test`
   passes with a `straggler.txt` fixture.
4. `decide` and `varka-matrix-rerun.yml`. Done when the selftest's decisions pass and
   `--dry-run` over run 44141461002 answers `stop: no straggler`.
5. After the merge (`workflow_run` reads the default branch), on the owner's word, one
   dispatch with the input `plant-straggler=b005`, which makes that bin
   sleep past its deadline: b005 fails with the annotation, the summary names it, and one rerun of
   b005 and the report follows. Done when that run's attempt 2 is green.

## 9. Questions for the owner

1. **Should CI rerun a straggler by itself?** Recommended: yes, once, as `varka-bench-cpu-retry`
   does for a CPU mismatch, with a dispatch input `rerun-stragglers` (default true) to turn it
   off. I cannot decide this because since 2 November CI starts only on the owner's word, and an
   automatic rerun is CI starting work. The plan is built on yes.
2. **The deadline: three times the plan, floored at 24 minutes.** Recommended as the row states
   it, with the floor because a bin planned at 2 minutes stopped at 6 would be a false straggler.
   It is the owner's call only if he wants a lower floor.

## 10. Done when

A bin whose units step runs past three times its plan (at least 24 minutes) stops itself, and the
run's report names it as a straggler with its CPU. The fork's CI reruns only the straggler bins
and the report, once, without the laptop. `varka-option-matrix.yml` carries the unit step.
