# VARKA-638: the arm sweep runs on vecbricks/varka's runners, not on the laptop

## 1. Where this came from

Row 638 of `PLAN.md`, added on 22 November 2026 by the owner's word, from the same day's option
matrices as row 637. `~/varka-ops/pre-pr.sh`, the local gate before every push, ends with an arm
sweep: every suite the branch adds or changes, under every configuration
(`dev/varka_matrix.sh --all -j 4 --suites <changed>`). It was added after three red matrices of
22 November (#1617, #1619, #1625) failed on configurations nobody had run locally. Four
branches' sweeps of 22 November took 12 to 58 minutes on the laptop
(`results/VARKA-638-sweep-sizes.txt`), each holding one of the laptop's few sbt slots, so
branches queue behind one another. vecbricks/varka has 20 Free-plan runners, idle on 22 November
except for the benchmarks.

## 2. The admission check, done

* **The sweep's size** (`results/VARKA-638-sweep-sizes.txt`, from the run directories of four
  branches' sweeps of 22 November). Row 611's sweep ran 10 catalyst suites under 59
  configurations in 11,656 JVM-seconds and one sql suite in 1,509, 55 minutes of laptop wall in
  all, its longest JVM 395 s. Row 345's swept one suite, `VarkaMathPortSuite`, in 13,651
  JVM-seconds and 58 minutes. Rows 504 and 600 took 13 and 12 minutes.
* **The sweep is row 637's first stage under another name.** Both plan the changed suites under
  every configuration without the reach record, and the planner can already pack them into
  bins (`dev/varka_matrix_bins.py plan --suites`). The row needs no second runner script, only
  a place to run that plan before the fork's matrix.
* **A vecbricks/varka run can test a pull request's head without a token.**
  `.github/workflows/benchmark.yml`'s `pr` input checks out `refs/pull/<n>/head` of
  vecbricks/varka, which GitHub keeps for every pull request opened against it. The benchmarks
  have dispatched this way since 10 November 2026.
* **It has no Build to reuse.** The test classes, the reach record and the harness store are
  artifacts of the fork's Build runs (`dev/varka_matrix_reuse.py`). A run on vecbricks/varka
  compiles for itself: 11 minutes in the matrix's first fanned-out run (row 507,
  `results/VARKA-507-run-40658422887.txt`). The sweep runs without the reach record anyway, so
  only the compile is lost.
* **A main-code-only branch sweeps nothing today.** `pre-pr.sh` prints that and moves on, as
  `varka-skips-union-w`'s log shows. The row keeps that behaviour (3.4).

What the check would have rejected: a sweep that waits for the fork's Build. On 22 November the
Builds waited over an hour behind the matrices, so such a sweep would gate nothing in time.

## 3. The design

### 3.1 Where: vecbricks/varka, as a mode of the option matrix workflow

`varka-option-matrix.yml` gains `stage: all | sweep` (default `all`), `pr` (a pull request number of
vecbricks/varka) and `sha` (that pull request's head, in full). With `stage=sweep`, the build job
checks out `refs/pull/<pr>/head` as the benchmarks do. It compiles the test classes itself, through
the same `--build-only` step the matrix falls back to today. It plans row 637's `first` list without
failed-run units: the changed suites from `dev/varka_matrix_scope.py`, plus `VarkaMatrixSuite` when
the options change, which is `pre-pr.sh`'s rule. The bins run as they do now at `max-parallel: 16`,
which leaves the build job, the report job and two runners for the benchmarks. The workflow declares
`permissions: contents: read` at the top, as `benchmark.yml` does, since a sweep runs a pull
request's test code; `report-failure` keeps its own wider block.

On vecbricks/varka the build job's guard admits only `workflow_dispatch` events with
`stage=sweep`, a `pr` and a `sha`. It refuses `schedule` there, which today's guard admits only
there, so the Sunday cron cannot start the full matrix once the workflow is enabled. The owner
left the workflow disabled there so that nothing starts a matrix on 20 slots by accident
(`VARKA-522.md` 7 question 3). The guard keeps that reason, but the workflow has to be enabled
for dispatches. That is question 1 of 6, and the enable waits until the guard has merged (7).

### 3.2 What it records

`run-name` carries the head SHA the run checked out: `Option matrix sweep of #<pr> at <sha>`.
The build job fails at once if `refs/pull/<pr>/head` is not the SHA given in a `sha` input, so
the name cannot lie about what ran. The report job's summary lists the swept suites and the
configurations.

### 3.3 How its result gates the pull request's matrix

The fork's matrix build job (row 637 3.4) looks for vecbricks/varka runs of the workflow whose
`run-name` holds its own head SHA, and takes the newest. A red sweep that was re-dispatched and
went green therefore counts as green. It reads the public API with the job's `github.token`.

* **Green:** `first` holds only the failed-run units, and the swept (configuration, suite) units
  are not rerun.
* **Red or still running:** the build job fails with the sweep's link before any bin starts.
  `scope=full` overrides it.
* **None, or a lookup that fails** (an API error, or the unauthenticated limit of 60 requests
  an hour, which a hosted runner's address shares): the fork runs the sweep as its own `first`
  stage, as row 637 designs. Nothing is blocked; only the saving is lost.

Reading another repository's runs with a job token is not proven for this setup. Step 1 probes
it. If the token cannot read them, the lookup goes unauthenticated, which the public API allows
at 60 requests an hour.

### 3.4 A diff that changes no suite

The sweep ends green at once with `nothing to sweep`, as `pre-pr.sh` does today, and the PR's
matrix runs ungated. Giving a main-code diff its suites is row 508's digest selection, outside
this row.

### 3.5 The laptop's side, outside the repository

`~/varka-ops/pre-pr.sh` drops step 5, and `open-pr.sh` dispatches the sweep after the pull
request opens. The matrix dispatch then waits for the sweep, not for the laptop. Those scripts
are the owner's operations directory, not this repository's, so the row's pull request changes
only the workflow, the planner and the plan. The `~/varka-ops` change is listed as its own step.
A finding on the way: `pre-pr.sh` adds `VarkaMatrixSuite` again when the branch already changes
it (the logs of rows 600 and 611 list it twice, `results/VARKA-638-sweep-sizes.txt`). The
sweep's planner keeps each suite once.

### 3.6 What is deliberately unchanged

The fork's matrix and its Build reuse. Row 522's weekly. Lint, Error Prone and the push check,
which stay on the laptop and take seconds to minutes.

## 4. Predictions, registered before the build

No calibration row prices a runner JVM against a laptop JVM (`dev/varka_calibration.py`). So the
predictions carry the ratio r, a runner JVM's seconds over the laptop's at `-j 4`, which step 2
measures. The prediction is r between 1 and 2.5. The JVM-seconds are from
`results/VARKA-638-sweep-sizes.txt`.

1. The build job takes 11 to 15 minutes, row 507's 11 plus a cache vecbricks/varka may not have
   warm.
2. Row 611's sweep, 13,165 JVM-seconds and 55 minutes on the laptop, takes about 23 minutes of
   wall at r = 1.5: 13 for build and setup, then 13,165 x 1.5 / 32 lanes, about 10. Its longest
   JVM, 395 s, is 10 minutes at that ratio, so it does not set the wall. Row 345's one-suite
   sweep, 13,651 JVM-seconds in 57 JVMs of up to 366 s, takes about the same. Neither uses the
   laptop's sbt time.
3. The fork's matrix loses its `first` stage for every swept head, with no cost to the fork.

## 5. Tests

* `dev/varka_matrix_bins.py --selftest`: a sweep plan holds every configuration of each changed
  suite once and no other suite.
* One dispatch on vecbricks/varka for an open pull request whose suites are known, timed and
  compared with that branch's laptop sweep. This is step 2's measurement and needs the owner's
  word to dispatch.

## 6. Questions for the owner

1. **Enable `varka-option-matrix.yml` on vecbricks/varka for sweep dispatches.** Recommended:
   yes, with the guard of 3.1. The alternative is a copy of the bins job in a file of its own,
   about 150 lines kept in step with the matrix's by hand.
2. **The benchmarks' share.** Recommended: the sweep takes 16 of the 20 slots, the build and
   report jobs one each at most, and a benchmark dispatched meanwhile takes one of the two left.

## 7. Sequencing

One pull request under 1,000 counted lines, after row 637's first pull request (the scope
script), since it plans from that script's suites.

1. Probe, one dispatch: does a fork job's `github.token` list vecbricks/varka's runs? Done when
   the answer and the request are in `results/VARKA-638-gate-probe.txt`.
2. The `stage`, `pr` and `sha` inputs, the guard that refuses `schedule` on vecbricks/varka, the
   permissions block, the run name, the sweep plan and the gate of 3.3. Done when it has merged.
3. On the owner's word, after step 2 merged: `gh workflow enable` on vecbricks/varka and one
   sweep dispatch for an open pull request. Done when its time and r are in
   `results/VARKA-638-sweep.txt`.
4. The `~/varka-ops` change (3.5), on the owner's word.

## 8. A note, not a row: pull request matrices on vecbricks/varka

Asked by the owner on 22 November 2026: could vecbricks/varka run whole PR option matrices, and
add its 20 slots to the fork's 40? Read from `results/VARKA-637-pr-matrix-scope.txt` and
`dev/varka_matrix_reuse.py`.

* **What blocks it.** A PR's test classes, its reach record and its harness store are artifacts
  of the fork's Build. `actions/download-artifact` can take another repository's artifact with
  `repository:` and `run-id:`, but only with a token that may read that repository's Actions.
  vecbricks/varka's job token cannot, and the owner chose no token secret (row 525). Without
  one, each matrix builds for itself (11 minutes before the first bin) and runs without a reach
  record. The master run 44485292430 ran without one: 191 bins and 1,457 bin-minutes, against a
  median of 171 and 1,275 for the completed PR runs with one, 14% more
  (`results/VARKA-637-pr-matrix-scope.txt`).
* **What it would give.** One matrix of about 1,450 bin-minutes on 18 of the 20 slots is at
  least 81 minutes of bins plus the build, against a 34-minute floor on the fork's 38 when the
  fork is free. It adds half again to the slots, but runs each matrix about twice as slowly as an
  idle fork would.
* **The token-free alternative.** vecbricks/varka runs work that needs no Build: this row's
  sweep, or the weekly full matrix on master. The weekly is the opposite of row 522's direction,
  which moves the weekly to the fork, so it is the owner's call and this plan does not edit
  row 522.
* **Verdict.** Not a row now. Rows 637 and 638 take about a seventh of the bin-minutes off the
  fork, more from red runs, and the sweep onto vecbricks/varka. If after them the fork's median
  bin wait still exceeds 30 minutes over a week, the cheap test is a one-job probe: a fine-grained
  token with read access to the fork's Actions, stored as a vecbricks/varka secret, downloading
  one Build's classes archive. That reverses row 525's choice, which only the owner can make.

## 9. Done when

A pull request's arm sweep runs on vecbricks/varka's runners from its head and is timed against
the laptop's. A red sweep stops the fork's matrix before its bins, a green one takes its units
out of it, and `pre-pr.sh` no longer runs the sweep.
