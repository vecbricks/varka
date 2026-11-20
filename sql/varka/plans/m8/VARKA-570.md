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
* stops the running units by the process group each one was started in, with TERM and then
  KILL after 30 s, never by pattern;
* prints `::error title=Straggler::<bin> ran <m> min against a plan of <p> min on <CPU>; stopped`
  and exits. After its `wait`, the step's main script finds `straggler.txt` and exits 3 instead
  of the 1 a killed unit's exit file would give, so the job's failure says straggler.

The unit loop changes with it. Today `run_unit` is started with a plain `&`, and the loop starts
the next queued unit whenever `wait -n` returns. Each unit is now started under `setsid`, so it
has a process group of its own, and its PID is recorded. The loop checks for `straggler.txt`
before it starts each unit, so units the watchdog's kill freed slots for are never started, and
names them in `straggler.txt` as not run. `wait -n` and the final `wait` take the units' PIDs,
so neither waits on the watchdog. After the final `wait`, the step kills the watchdog by its
recorded PID: a bin on time ends when its units do, not at its deadline.

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

`decide` also answers `stop` when the run's title says the rerun is off. `workflow_run` and the
runs API do not expose a dispatch's inputs, so the matrix carries the choice in its `run-name`,
which the event's `display_title` does give: the dispatch input `rerun-stragglers` (default
true) appends ` (no rerun)` when it is `false`. The expression compares
`github.event.inputs['rerun-stragglers'] == 'false'` as a string, so a scheduled run, which has
no inputs, keeps the rerun. A second dispatch input, `plant-straggler` (a bin name, default
empty), makes that bin's units step sleep past its deadline before its units, for step 5.

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
| `.github/workflows/varka-option-matrix.yml` | watchdog, units, inputs, upload, summary, merge |
| `.github/workflows/varka-matrix-rerun.yml` | `workflow_run`, `actions: write`, the rerun |
| `dev/varka_matrix_stragglers.py` | `history`, `decide`, `--selftest` |
| `dev/varka_ci_ledger.py` | the "Slow" class from the `Straggler` annotation (11.1) |
| `dev/varka_bin_units_test.py` | the watchdog's cases, over the step's own text (11.1) |
| `.github/workflows/build_and_test.yml` | the stragglers selftest beside the CPU retry's |
| `results/VARKA-570-units-history.txt` | step 1's table |
| `sql/varka/skills/testing-and-debugging.md` | a section: what a straggler line means |

## 5. Tests, and what each is for

* `dev/varka_matrix_stragglers.py --selftest`: the decisions over recorded run shapes. A run with
  one straggler gives `rerun`. A straggler beside a test failure gives `stop`, since the test
  failure is not the runner's, and so does a failed report job beside a straggler. Attempt 2
  gives `stop`. A cancelled run gives `stop`. The step
  and annotation names it keys on are read from the workflow file, so a rename fails the test.
* The watchdog's shell function, run locally by the selftest with a 5 s deadline over three
  `sleep` units on two slots: the marker file, the exit 3, no process left in either group, and
  the third unit never started. A second case with units shorter than the deadline ends within
  1 s of its units, with no marker file, which catches a step that waits on its watchdog.
* `actionlint` over both workflow files.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. Step 1's history over the 13 runs of section 2: no green bin's units step passes 24 minutes.
   b094 of run 44141461002 is the closest, 21 to 23 minutes. If one passes, the floor rises to
   the next multiple of 480 s above it, and the plan says so.
2. The planted straggler of step 5 is stopped within 1 minute of its deadline. The run completes
   within 5 minutes after that, and the rerun starts within 5 minutes of the run's completion.
3. The watchdog's cost on a bin that is on time is under 1 s, one `sleep` loop per bin, killed
   when the units end.

### 6.2 Scored

1. **Wrong.** `dev/varka_matrix_stragglers.py history` read 19 of the 20 runs of section 2 (the
   admission check's read had 13; run 43645907563 answered HTTP errors), 3,049 bins' units steps
   (`results/VARKA-570-units-history.txt`). Two green bins passed 24 minutes, both b092: 24.1 in
   run 43110513436 and 24.3 in run 43533941181. b094 of run 44141461002 took 23.4 in its units
   step. The x86 bins' median per run was 6.0 to 7.4 minutes and their longest 14.9 to 24.3; the
   four Arm platform bins per run took 10.1 to 10.9 at the longest. The planner's seconds for b092
   are not in the jobs API, so whether its plan was above 480 s is not known. By the rule this
   prediction set, the floor rises to the next multiple of 480 s above 24.3 minutes: 1,920 s, 32
   minutes, four lanes. b023's 43 minutes and b021's 91 would both still have been stopped.
2. Owed: step 5, after the merge.
3. Owed on a runner: step 5's run gives the on-time bins' units-step times beside this window's.
   Locally the selftest's on-time case (three 1 s units on two slots under a 30 s deadline) ends
   in under 4 s, so the step waits on neither the watchdog nor its sleep.

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
   does for a CPU mismatch, with a dispatch input `rerun-stragglers` (default true, carried in the
   run's title as 3.3 says) to turn it off. I cannot decide this because since 2 November CI starts
   only on the owner's word, and an automatic rerun is CI starting work. The plan is built on yes.
2. **The deadline: three times the plan, floored at 24 minutes.** Recommended as the row states
   it, with the floor because a bin planned at 2 minutes stopped at 6 would be a false straggler.
   It is the owner's call only if he wants a lower floor.

## 10. Done when

A bin whose units step runs past three times its plan (at least 24 minutes) stops itself, and the
run's report names it as a straggler with its CPU. The fork's CI reruns only the straggler bins
and the report, once, without the laptop. `varka-option-matrix.yml` carries the unit step.

## 11. Built, 20 November 2026

Steps 1 to 4 are built, in two pull requests, since the one the plan sized at about 400 counted
lines came to about 1,030: part A on branch `varka-570-straggler-bin-rerun` (steps 1 to 3: a
straggler stops itself and is named, and its run's failed jobs can be rerun by hand at once) and
part B stacked on it, `varka-570b-matrix-rerun` (step 4: CI reruns it). Step 5 waits for both
merges and the owner's word.

* Step 1: `dev/varka_matrix_stragglers.py history`, and its table in
  `results/VARKA-570-units-history.txt`; prediction 1 is scored in 6.2.
* Step 2: the watchdog, the unit loop under `setsid`, the planted straggler, and the upload of
  `straggler.txt` (without `overwrite`, item 4 of the next section). `dev/varka_bin_units_test.py --selftest`
  passes with five new cases (class `Watchdog`): a bin past a 3 s deadline with one unit deaf to
  TERM ends with exit 3, both units' groups gone and the third unit never started; a finished unit
  is listed with its exit and seconds; a bin on time ends well before its 30 s deadline (about 2 s,
  bounded at 15 for a loaded machine) with no marker; a planted straggler starts no
  unit; another bin's plant changes nothing. Removing the KILL pass, or the step's stop of its
  watchdog, fails a case; a group still holding a process is read from `/proc`, zombies aside,
  since a killed orphan nobody has reaped yet still answers `killpg(group, 0)`. The five cases
  take 16 s on the laptop, and the file's other 13 cases 11 s, so the Build's house-rules
  step grows by about 16 s (that step has no line in `ci/step-budget.tsv`). `actionlint` passes on
  both workflow files.
* Step 3: the summary's "Stragglers" block, and the ledger's `slow` line from the annotation.
  `python3 dev/varka_ci_ledger.py --self-test` passes with the new case.
* Step 4 (part B): `decide` and `.github/workflows/varka-matrix-rerun.yml`;
  `python3 dev/varka_matrix_stragglers.py --selftest` passes (18 cases: the decisions of section
  5, the rerun acted on once, a run rerun by hand meanwhile, the history's rows, the report's
  handling of a stopped unit, and the names read from the workflow files). The selftest runs in
  the Build's "house rules" step beside the CPU retry's. `decide --rerun --dry-run` over run
  44141461002 answered `stop: not only stragglers failed: Option matrix: b053 failed its units
  without a straggler annotation; Option matrix: report is failure`, since that run failed a test
  in b053; over run 44485292430 `stop: run concluded success`; over run 40942811546, the b021
  straggler, `stop: run concluded success`, since its attempt 2 passed.
* `sql/varka/skills/testing-and-debugging.md` has the section on reading a straggler line
  (part B, since it reads the rerun too).

### 11.1 Where the plan was wrong

1. **The floor** is 32 minutes, not 24 (6.2, prediction 1).
2. **The report's merge keeps a straggler's units.** Section 3.3 had the merge leave them out so
   that the report would not fail on them. It does not fail on them as they are. `run_one` in
   `dev/varka_matrix.sh` writes a JVM's `exit`, and its configuration's `name`, after the JVM's
   subshell returns, and a TERM to the unit's group ends that shell first: the same shape run under
   `setsid` and stopped by its group left neither file (laptop, 20 November 2026). So
   `dev/varka_matrix_report.py` lists the JVM as "did not finish", or leaves out a configuration
   none of whose JVMs finished, and fails on neither, while the declining check only loses the
   baseline tests a stopped defaults JVM did not reach. Leaving the whole bin out would also drop
   a unit that finished and failed a test before the stop, and then `decide` would rerun a run
   with a real failure. So nothing is left out, and `ReportTest` in the selftest holds both
   halves.
3. **The ledger reads the annotation, not `straggler.txt`.** The annotation is on the check run
   of the attempt that straggled, where `failed_job` already looks, while the ledger's artifact
   download takes the newest artifact of a name, which after a rerun is the rerun's. So the
   watchdog puts the minutes, the plan and the CPU in the annotation.
4. **Risk 2 does not arise, and `overwrite: true` is left out.** Run 40942811546 keeps two
   artifacts named `varka-option-matrix-b021`, one per attempt: a rerun's upload of the same name
   neither fails nor replaces the first. Its attempt 2 report job's download step "Found 113
   artifact(s)" and downloaded b021 once, the rerun's (ID 12934912599), so the report already takes
   the newest of each name. `overwrite` would only delete attempt 1's artifact, the straggler's
   record that a cancel used to lose.
5. **The watchdog's tests are in `dev/varka_bin_units_test.py`**, not in the stragglers selftest:
   that file already runs this step's text from the workflow against a stub `varka_matrix.sh`.
6. **`decide --rerun` makes the rerun itself** (`POST .../rerun-failed-jobs`, the call `gh run
   rerun --failed` makes), after reading the run again, so that a dry run and the rerun share one
   path.
7. **Pages of 50 jobs.** A page of 100 jobs of run 40942811546's attempt 2 answered 502 on three
   reads, while pages of 50 and of 30 answered at once, so the tool lists jobs 50 at a time, and
   `decide` lists none when the run itself answers `stop`.
8. Step 4's expected answer over run 44141461002 was `stop: no straggler`; that run failed a test,
   and `decide` says so.

### 11.2 Left

* Step 5, on the owner's word after the merge: one dispatch with `plant-straggler=b005`; done when
  that run's attempt 2 is green. It scores predictions 2 and 3, and its attempt 2 report shows
  the download taking the rerun's b005 alone.
* A scheduled run with a straggler has its `report-failure` job open an issue before the rerun
  turns the run green: that job runs when the run ends, and the rerun starts after. Not changed
  here; if the issue proves noise, the job can skip a run whose only failures are stragglers.

## Decisions taken while the owner was away (20 November 2026)

1. **Question 1, CI reruns a straggler by itself:** built as recommended, once, with the dispatch
   input `rerun-stragglers` (default true) to turn it off. The plan was built on yes, and nothing
   runs until the merge and step 5's dispatch, which need the owner's word anyway.
2. **Question 2, the deadline:** three times the plan, with the floor at four lanes (32 minutes)
   rather than three, because the plan's own rule for prediction 1 says so (6.2).
3. **No exclusion in the report's merge, no `overwrite`, the ledger from the annotation, the
   watchdog's tests in the existing bin-step test:** 11.1 items 2 to 5, each because the plan's
   version would have dropped a real failure, deleted the straggler's record, read an artifact the
   ledger does not download, or duplicated a harness.

