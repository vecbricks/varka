# VARKA-522: The weekly option matrix runs on the fork, after the pull requests' matrices

## 0. What exists now (admission check)

From master at `239995f4fe4`, and from the MaxGekk/spark and vecbricks/varka Actions runs named
below, read on 1 November 2026 with `gh api` GETs. Nothing was dispatched.

1. **The fork's master follows Varka's.** The fork's default branch is `master`, at
   `239995f4fe4`, the same commit as vecbricks/varka's master. `varka-fork-sync.yml` is active on
   the fork. Its last six runs succeeded: one scheduled (41285601583) and five dispatched by hand,
   the newest 41298580352 on `9c9f6ef5895`. Each fast-forward dispatches `varka-master.yml` on
   the new head. So row 525's step (1) holds in practice, by the owner's manual syncs.
2. **The fork's master Build is a `varka-master.yml` run, and it leaves what the matrix reuses.**
   Run 41294586758 (head `9c9f6ef5895`, 09:46 to 10:02 UTC) ran "Run / Varka option matrix
   classes" and the three "Run / Varka suites: sql" jobs, and uploaded these artifacts:
   - `varka-option-matrix-build`, 354 MB;
   - `varka-matrix-reach-sql-{1,2,3}of3`;
   - `varka-harness-store-sql-*`.

   The last five completed such runs took 16 to 77 minutes (41298628068, the sixth, was still
   running).
3. **But the matrix's lookup does not see it.** `dev/varka_matrix_reuse.py` keeps only the runs
   whose `path` is `.github/workflows/build_main.yml` (`:67`, `:178-183`). On the fork,
   `build_main.yml` skips master (row 525), so a scheduled run on the fork's master finds "no
   Build of this commit", compiles its own classes and has no reach record. That is the 126-bin
   kind of run, 71.2 minutes against 35.8 with the record (`m8/results/VARKA-matrix-bins-1030.txt`,
   the runs table). The job names do match: the lookup compares the part after `" / "`
   (`:136`, `:250`, `:259`).
4. **The artifacts last a day.** `varka-option-matrix-build` and `varka-matrix-reach-*` are
   uploaded with `retention-days: 1` (`build_and_test.yml:1498` for the archive, `:1718` for the
   reach). A
   Sunday run whose master has not moved since Saturday morning finds them expired.
5. **The schedule's guard.** `varka-option-matrix.yml`'s build job runs a `schedule` event only
   on vecbricks/varka (`:31-46`). The fork already carries the file at its master and fires the
   cron: run 41296023350, on 1 November 2026 at 10:06 UTC, ended `skipped` at the guard. The cron
   says Sunday 03:00 UTC, and the runs fired late. vecbricks/varka's last three scheduled runs
   started at 08:18, 07:56 and 09:53 UTC (40176071516, 39109529723, 38129966925), and the
   fork's at 10:06.
6. **On vecbricks/varka the workflow is `disabled_manually`** (since 29 October 2026,
   `m8/results/VARKA-522-weekly-run.txt`). Its last scheduled run, 40176071516, took 211 minutes
   on the twelve shards from before the fan-out.
7. **Matrices overlap on the fork today.** On 1 November 2026, PR matrices 41292290507 (09:15 to
   09:45), 41288798840 (09:25 to 09:51) and 41294753812 (09:49 to 10:15) ran partly side by side.
   The workflow has no `concurrency` group. What serializes them is the push chains' gate
   (`~/proj/.varka-chains/chain3.sh:13`, `matrix_running`, any run of the workflow not
   completed) and the owner's practice.
8. **The slot budget.** The bins job has `max-parallel: 30` (`varka-option-matrix.yml:261`),
   a literal. Row 505 (planned) adds an instruction-set axis of about 8 bins with slots of its own
   beside the 30, on every dispatch. By the owner's word of 1 November 2026 (`VARKA-505.md` 7,
   question 1, on branch `varka-m8-triage`), rows 522 and 531 then wait for a PR matrix to finish
   rather than run beside one.
9. **The branch.** `varka-522-weekly-slots` holds one commit, the re-scope `e805afa2ecf`, merged
   as #1141. Its PLAN.md row is master's. Nothing on it is unmerged.

### What differs from the row text

- Step (1)'s precondition is met by practice, not yet by schedule: the fork's master follows by
  the owner's dispatches of `varka-fork-sync.yml` (row 525). But the schedule's reuse fails even
  so (item 3). The matrix's lookup has to accept `varka-master.yml` runs, and has to cope with
  item 4's one-day artifacts.
- Step (2), "in the slots it finds free, up to 38, a dispatched run keeping 30", predates the
  owner's 1 November decision. A weekly run does not start beside a PR matrix. It waits for one
  to finish, then takes the matrix's own shape. That shape is 30 bins, plus the axis's slots once
  row 505 lands, and less only where other runs (PR Builds, upstream pull requests' CI) hold
  slots when it plans. "Up to 38" survives only as the bound before row 505 lands.
- The row's 211-minute figure is vecbricks/varka's, on twelve shards. The fork's reach-filtered
  dispatches run in about 36 minutes (`VARKA-matrix-bins-1030.txt`), which is the comparison
  the timing step reads against.

## 1. Goal and non-goal

**Goal.** Every Sunday the option matrix runs on the fork, from the fork's master. It reuses that
master's Build classes and reach record, starts only once no other option matrix is in flight,
and takes the slots free when it plans, within the matrix's shape. vecbricks/varka runs no
matrix.

**Non-goal.** No change to a dispatched run's shape or to the PR Build. No per-merge master
matrix (row 531). No baseline kept for pull requests (row 531's steps 2 and 3).

## 2. Design

### 2.1 The guard turns to the fork

The build job's condition admits `schedule` on `MaxGekk/spark` instead of vecbricks/varka, and
the comment says why: the fork has 40 slots and the master Build, and vecbricks/varka has
neither. On vecbricks/varka the workflow stays `disabled_manually` (question 3).

### 2.2 The lookup reads the master Build

`varka_matrix_reuse.py` accepts a run of either `build_main.yml` or `varka-master.yml` for the
commit. A selftest case covers a `varka-master.yml` run holding the archive. When the scheduled
run's lookup finds the commit's master Build with the archive expired, or no master Build at
all, the build job dispatches `varka-master.yml` on `master` (`actions: write`, as
`varka-fork-sync.yml` already does) and waits for it. The wait is bounded by the build job's
timeout, raised for the scheduled path to cover the master Build's 77-minute worst case of item
2. The wait is a job of its own (2.3) because GitHub caps a job at 360 minutes: five hours of
waiting, the master Build and the plan would not fit in one. A dispatch falls through to
compiling on its own, as today (question 2).

### 2.3 The weekly waits for the other matrices

A job of its own, `wait`, ahead of `build` and run only on the scheduled path, polls every two
minutes until no other run of
`varka-option-matrix.yml` is `queued` or `in_progress`, using the same test as the chains'
`matrix_running`. It does the same for an upstream pull request's Build on the fork
(`upstream-pr-ci-shares-fork-slots`: such a Build doubled a matrix's wall). It stops waiting
after five hours, ends with a warning annotation naming the runs it waited on, and does not run
that week (question 1). While it waits it holds one slot, and the chains' `matrix_running` sees
it, so the PR matrices dispatched after it queue behind it. The matrices then run one at a time
in both directions.

### 2.4 The parallelism from the free slots

After the wait, the plan step counts the fork's jobs `queued` or `in_progress` across its runs.
It sets the bins' parallelism to the smaller of the matrix's shape and 40 less those jobs, less
the build and report jobs' two. The shape is 30, or 38 before row 505's axis lands, since then
the axis has no slots to keep. A dispatched run keeps 30. The number goes to the bins job as
`max-parallel: ${{ fromJSON(needs.build.outputs.parallel) }}` (**[I]**: the plan did not check
that `strategy.max-parallel` takes an expression over `needs`. If it does not, the bins split
into a 30-wide job and a second job for the rest, as row 505's axis is planned). The planner
gets `--slots N` and picks its bin target so the bins fill whole waves of `N`. The run's
summary prints `N`, the jobs it counted and the wave count.

### 2.5 A dispatch that takes the scheduled path

A `workflow_dispatch` input, `as-weekly`, default off, runs the scheduled path on a dispatch.
That lets the pull request prove 2.2 to 2.4 on the fork's master without waiting for a Sunday,
and lets the owner rerun a week the wait skipped.

## 3. Tests and proofs

1. `dev/varka_matrix_reuse.py --selftest`: a `varka-master.yml` run is found. An expired archive
   is reported as `expired`, a new `missing=` value, distinct from `none`.
2. `dev/varka_matrix_bins.py --selftest`: `--slots N` gives whole waves, and the default keeps
   today's plan byte-identical.
3. An `as-weekly` dispatch on the fork's master after the merge. It must show the wait step's
   log, `parallel=` at or under 38, the reach of a `varka-master.yml` run, and its wall.
4. The done-when's two scheduled runs, 8 and 15 November 2026, each timed: wait, build, bins and
   report.

## 4. Predictions and costs, registered before the build

- P1: a scheduled run with the master Build's reach plans about the 106 bins of a
  reach-filtered dispatch, and runs within 5 minutes of its wall at the same parallelism, about
  36 minutes (`VARKA-matrix-bins-1030.txt`, run 41035158315).
- P2: at 38 slots, before row 505, the bins phase is shorter by at most a fifth of the 30-slot
  run's. Fewer waves are the gain, and the slowest bin bounds it.
- P3: the wait step adds nothing on a quiet Sunday and up to its five-hour bound on a busy one.
  Item 5's fire times put the cron in the owner's morning, when the chains run, so at least one
  of the two timed runs waits more than ten minutes (**[I]**: from one Sunday's runs, item 7).
- P4: a re-dispatched master Build costs its classes and sql jobs once a week, about 15 job
  minutes, only on a Sunday whose master is a day old.

## 5. Order, files and size

| File | Change |
|---|---|
| `varka-option-matrix.yml` | guard, wait step, free slots, `max-parallel`, `as-weekly` |
| `dev/varka_matrix_reuse.py` | `varka-master.yml` runs, `expired`, selftests |
| `dev/varka_matrix_bins.py` | `--slots`, selftests |
| `sql/varka/skills/` (the CI file) | where the weekly runs and how it waits |
| `sql/varka/plans/m8/results/VARKA-522-weekly-run.txt` | the two timed runs, appended |

Order:

1. the reuse lookup and the planner, each with its selftests;
2. the workflow;
3. the `as-weekly` dispatch on the fork's master after the merge;
4. the two Sundays.

Size: small, as the row says. It is three files and one waiting step.

## 6. Out of this row

- Row 531's per-merge master matrix and its baseline. Row 531's step 4 decides whether this
  weekly run stays.
- Row 525's schedule of the sync itself (the hourly sync runs, and the fast-forward is manual
  where GitHub refuses the token).
- Changing the cron's hour.

## 7. Questions for the owner

1. **When the wait gives up.** After five hours with a matrix still in flight, should the weekly
   (a) skip that week with a warning, (b) run anyway beside the other matrix, or (c) wait
   without a bound up to the job's six-hour limit? Recommended: (a). (b) breaks the 1 November
   rule, and `as-weekly` reruns a skipped week by hand.
2. **The expired archive.** Should the weekly (a) re-dispatch the master Build and wait (2.2),
   (b) keep master's classes archive and reach for 8 days, at 354 MB per master Build and several
   Builds a day, or (c) compile for itself and run without reach? Recommended: (a). It costs one
   Build a week, where (b) holds gigabytes of artifacts.
3. **vecbricks/varka's workflow.** With the guard turned, a schedule there would be skipped
   anyway. Should it stay `disabled_manually`, or be enabled for dispatches? Recommended: leave
   it disabled, so nothing starts a matrix on 20 slots by accident, and say so in the workflow's
   comment.
4. **The weekly's shape once row 505 lands.** Recommended: the dispatch shape, 30 bins plus the
   axis's own slots, so the weekly also covers the instruction sets. The row's "up to 38" then
   applies only before the axis.

## 8. Done when

- the Sunday schedule runs on the fork and is skipped on vecbricks/varka;
- a scheduled run reuses the classes and reach of a `varka-master.yml` Build of its own commit,
  re-dispatching it when the artifacts have expired;
- it waits for any other option matrix to finish, and plans its parallelism from the free slots
  within the matrix's shape;
- two scheduled runs are timed in `m8/results/VARKA-522-weekly-run.txt`.

## 9. Open points for the lead

1. Row 525's cell says the schedules are "guarded to vecbricks/varka, which row 522 has to turn
   for the option matrix". This plan turns it. The weekly Spark matrix (`varka-weekly-matrix.yml`)
   keeps its guard, which is not this row's.
2. The cron fires five to seven hours late (item 5). If the owner wants the weekly to finish
   before his Sunday morning, the hour is worth moving. That is a one-line change, left out
   here (section 6).
3. With 2.3 the weekly holds one slot while it waits, and the chains' gate sees it as a running
   matrix. A chain started after the weekly's start therefore waits for the weekly. That is the
   intended order, but it is a change of behaviour the chains' owner should know.

## 10. Outcome
