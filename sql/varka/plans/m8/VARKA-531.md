# VARKA-531: A matrix on the fork's master after each merge, the baseline a PR's matrix reads

## 0. What exists now (admission check)

From master at `5c660201b35`, and from MaxGekk/spark's Actions runs, read on 1 November 2026 with
`gh api` GETs. Nothing was dispatched.

1. **How the fork's master moves, and what it runs.** The fork's master follows vecbricks/varka's
   by `varka-fork-sync.yml` (row 525), run by the owner's dispatches. The sync dispatches
   `varka-master.yml` on the new head. Every `varka-master.yml` run on the fork that ran since 30
   October 2026 is a `workflow_dispatch`; a push there ends `skipped`. So "after every merge"
   means after every sync today: a sync that takes three merges runs one master Build.
2. **The master Build is the matrix's input.** Each such run uploads the option matrix's classes
   archive and the reach record (`VARKA-522.md` 0, item 2). It runs under a workflow-level
   `concurrency` group, `varka-master-${{ github.ref }}`, with `cancel-in-progress: false`. So
   one run is in progress, one waits, and a newer one replaces the waiting one. That is the
   behaviour the row asks of the master matrix.
3. **The load, from the listings of 29 October to 1 November 2026** (dispatched, not cancelled,
   whole minutes from `run_started_at` to `updated_at`):

   | day | PR matrices | their mean wall | master Builds | their mean wall |
   | :-- | --: | --: | --: | --: |
   | 29 October | 16 | 35 min | - | - |
   | 30 October | 23 | 28 min | 15 | 22 min |
   | 31 October | 13 | 26 min | 7 | 17 min |
   | 1 November (to 18:21 UTC) | 17 | 34 min | 7 | 29 min |

   A master matrix of the same shape after each master Build would have added 7 to 15 runs a
   day of about half an hour each, beside 13 to 23 pull-request matrices.
4. **What exists of each step.**
   - Step (1)'s dispatch has no code. The matrix's lookup accepts only `build_main.yml` runs
     (`dev/varka_matrix_reuse.py:67`, `:178-183`). Row 522's plan (`VARKA-522.md` 2.2) makes it
     accept `varka-master.yml` runs.
   - The wait for other matrices is row 522's `wait` job (`VARKA-522.md` 2.3): lower run ids
     first, a higher id ahead only once past its own wait, a plain dispatch always ahead.
   - Step (2)'s baseline: a matrix run's artifacts already hold what a baseline needs.
     `varka-option-matrix-reach` (30 days) holds the reach the configurations ran by,
     `varka-option-matrix-report` (30 days) the report and the suite seconds, and each bin's
     `varka-option-matrix-<bin>` (30 days) its runs and JUnit reports
     (`varka-option-matrix.yml`, the upload steps). Row 508's per-test digests are not built yet
     (row 508: "left: the CI wiring, which needs a master matrix's record as the baseline").
   - Step (3)'s failures: `dev/varka_ci_ledger.py run RUN_ID` (row 530) prints one run's failed
     tests as (configuration, suite, test) lines from the bins' JUnit reports, without writing
     the ledger.
5. **The owner's rule of 1 November 2026** (row 505, `VARKA-505.md` 7 on branch
   `varka-m8-triage`): row 505's instruction-set axis takes about 8 slots beside the matrix's
   30, and rows 522 and 531 wait for a pull request's matrix to finish rather than run beside
   it.

### What differs from the row text

- The row's size is small. With the trigger, the replacement, the baseline lookup, the report's
  marking and the weekly's fate, this plan reads it as **medium** (section 5).
- Step (2) says "the latest green one replacing the last". A baseline that holds only green runs
  holds no failures, so step (3) could never mark one as "also on master". The plan keeps two
  pointers (2.3, question 1).

## 1. Goal and non-goal

**Goal.** Each master Build on the fork is followed by one option matrix of the dispatch shape
on that master commit, unless a newer master has replaced it. It waits for any pull request's
matrix, as row 522's weekly does. Its results are found by a pull request's matrix, whose report
marks each failure as on master's baseline or new to the pull request.

**Non-goal.** Row 508's selective rerun (it gets its baseline from here). The report's own format
beyond the marking. vecbricks/varka, which stays without a matrix (`VARKA-522.md` 7, question 3).

## 2. Design

### 2.1 The trigger

`varka-master.yml` on the fork gains a last job, after `run-build` has succeeded. It dispatches
`varka-option-matrix.yml` on `master` with a new input `baseline: true` (`actions: write`, as
`varka-fork-sync.yml:136` does for `varka-master.yml`). A dispatch is used rather than a
`workflow_run` trigger. A run started by a `GITHUB_TOKEN` call starts others only through
`workflow_dispatch` and `repository_dispatch`, and whether a `workflow_run` fires after a
dispatched run is **[I]**. On vecbricks/varka the job does not run.

### 2.2 One at a time, the newest only

The option matrix workflow takes a workflow-level `concurrency` group on the baseline path alone:
`varka-matrix-baseline` when `inputs.baseline`, and the run's own id otherwise, so pull requests'
runs are not grouped. It does not cancel in progress, as `varka-master.yml`'s group does not
(item 2), so a newer baseline dispatch replaces a waiting one. Whether a workflow-level
`concurrency.group` may read `inputs` is **[I]**. If it may not, the group moves to the jobs.

A run in the group is "in progress" while its `wait` job waits. So after the wait the build job
reads the fork's master head. If that head is not this run's commit and a newer baseline run
exists, the run ends as superseded, with a notice, before it plans a bin. The newer run then
takes the slot.

### 2.3 The wait and the shape

The baseline path takes row 522's `wait` job as the weekly does (`VARKA-522.md` 2.3), on the
same rule: a pull request's matrix dispatched while it waits goes ahead of it. The baseline and
the weekly order among themselves by run id. Its shape is a dispatch's, 30 bins plus row 505's
axis once that lands. Its reach is the master Build's, through row 522's lookup. If row 522 has
not landed, this row builds 2.2 and 2.3 of `VARKA-522.md` under those names, and row 522 then
uses them.

### 2.4 The baseline: two pointers, both lookups

Nothing is copied or cached. The fork's cache is at its quota (`results/VARKA-525-fork-cache.txt`:
63 entries against the 10 GB quota). A baseline is a lookup over the option matrix's runs on the
fork whose input `baseline` was true. The run's `display_title` or `name` carries a
`baseline <sha>` marker, set through `run-name`, so the lookup can tell them apart.

- **The failure baseline:** the newest completed baseline run, green or red, on a commit that is
  an ancestor of the pull request's head. Its failures come from `dev/varka_ci_ledger.py run
  <id>`.
- **The selection baseline** (for row 508): the newest green baseline run on such an ancestor.

`dev/varka_matrix_reuse.py` gains a `baseline` command printing both run ids and their commits,
with selftests over fixtures of the runs listing.

### 2.5 The report marks each failure

The report job of a pull request's matrix runs the lookup. It reads the failure baseline's lines
and appends to the report and the summary, per failed (configuration, suite, test), either `also
on master at <sha>` or `new`. A line also names the baseline's commit and how many commits it
lies before the pull request's merge base, so a stale baseline is visible. With no baseline found
the report says so and marks nothing. A baseline run's own report marks nothing.

### 2.6 Step 4: the weekly

Row 522's Sunday run is kept as a fallback. Its build job looks up the newest green baseline run
on the current master head. If there is one from the last seven days, the weekly ends with a
notice. Otherwise it runs as row 522 plans (question 3).

## 3. Tests and proofs

1. `dev/varka_matrix_reuse.py --selftest`: the `baseline` command over fixtures of a listing with
   red, green, superseded and non-ancestor runs.
2. The report's marking, as a selftest over two fixture ledgers.
3. After the merge: two syncs, each followed by a baseline run on the fork's master, timed; a pull
   request's matrix after them whose report names the baseline. These are the done-when's runs.
4. A planted case (row 504's convention, plan on branch `varka-m8-triage`): a pull request's
   matrix with a test made to fail that also fails on the baseline must show it as `also on
   master`, and one made to fail only on the branch as `new`.

## 4. Predictions and costs, registered before the build

- P1: a baseline run's wall, past its wait, is a dispatch's on the same reach. That is about 36
  minutes (`results/VARKA-matrix-bins-1030.txt`, the runs table) and 26 to 35 minutes as the
  mean of the dispatches of item 3.
- P2: on a day like 30 October 2026 (15 master Builds), replacement keeps the baseline runs to
  fewer than the Builds. At least a third of them end superseded or replaced (**[I]**: the
  Builds' spacing was not modelled).
- P3: the owner's chains hold a pull request's dispatch while any matrix run is not completed
  (`VARKA-522.md` 0, item 7), so a baseline run in its bins delays the next pull request's matrix
  by up to one wall, about half an hour (question 2).

## 5. Order, files and size

| File | Change |
|---|---|
| `varka-master.yml` | the dispatch job, fork only |
| `varka-option-matrix.yml` | `baseline` input, `run-name`, group, superseded check, marking step |
| `dev/varka_matrix_reuse.py` | `baseline` command, selftests |
| `dev/varka_matrix_report.py` or a new step | the marking |
| `sql/varka/skills/` (the CI file) | where the baseline comes from and how a failure is marked |
| `sql/varka/plans/m8/results/VARKA-531-baseline.txt` | the done-when's runs, timed |

Order: after row 522's lookup and `wait` job; the lookup and the marking with selftests; the
workflow; the two syncs.

Size: medium. It is two workflows, two scripts and the interaction with rows 508, 522 and 530.

## 6. Out of this row

- Row 508's use of the selection baseline.
- Making the sync itself run after every merge (row 525).
- A baseline on vecbricks/varka.

## 7. Questions for the owner

1. **Which run is the baseline.** (a) Two pointers: the newest completed baseline for failures,
   the newest green one for row 508's digests (this plan). (b) Green only, as the row says, which
   leaves step (3) nothing to mark. Recommended: (a).
2. **A pull request's matrix while a baseline runs.** (a) The chains hold it, as today, up to one
   baseline wall. (b) The baseline's build job ends itself as superseded if a pull request's
   dispatch is waiting when it would start its bins. (c) The chains' gate ignores baseline runs,
   so the two run side by side on 40 slots, which the 1 November rule excludes once the axis
   lands. Recommended: (a) at first, with P3 measured. If the delay shows on a busy day, then
   (b).
3. **Row 522's weekly.** (a) Kept as a fallback when no green baseline ran in seven days (2.6).
   (b) Dropped. (c) Kept as planned. Recommended: (a). A week without merges still gets a run on
   the week's runner images.
4. **Per merge or per sync.** While the sync is dispatched by hand (row 525), a baseline follows
   each sync, not each merge. Is that enough for the done-when's "two merges", or does the row
   wait for the sync to run per merge? Recommended: per sync is enough. The replacement would
   collapse a burst of merges to one run anyway.

## 8. Done when

- two master syncs are each followed by a full option matrix on the fork's master, timed in
  `results/VARKA-531-baseline.txt`;
- a pull request's matrix after them finds the baseline, and its report marks a failure that
  master also has as `also on master`;
- the weekly's fate (question 3) is in the workflow.

## 9. Open points for the lead

1. Item 3's counts are from the listings of one read, not a committed file. If the owner wants
   them kept, they belong in `results/VARKA-531-baseline.txt` with the done-when's runs.
2. The baseline path and row 522's weekly path share the `wait` job and the lookup. The two rows
   should be built in that order, by one owner or one after the other, since they edit the same
   workflow file.

## 10. Outcome
