# VARKA-590: a pull request's Build tests its head merged with Varka's master

*Moved to milestone 9 on 23 November 2026 with row 590 (`SCOPE.md` item 138). A bare
`PLAN.md`, `results/` path or other `VARKA-<n>.md` below names milestone 8's, in `m8/`,
unless that plan moved too (`SCOPE.md` item 138).*

## 1. Where this came from

Row 590 of `PLAN.md`, added on 9 November 2026 on the owner's word, from the Builds of that day.
`.github/actions/checkout-and-sync` checks out the triggering commit with no merge. It stopped
merging onto apache/spark's master, which had failed every branch whenever upstream edited a
file Varka rewrote. So a branch is tested without the fixes master gained after it forked. On 9
November #1413's Build hung 2.5 hours, and #1418's and #1426's failed, in `VarkaWidthAuditSuite`'s
forced-width census. That was the hang #1416 had fixed on master that morning, and each branch
needed a merge of master and a re-push. And two pull requests green alone can break master
together (#1392 and #1393, 8 November).

## 2. The admission check, done

* **The problem holds on master.** The action's step "Checkout this repository's commit" checks
  out `github.sha` for every repository but apache/spark, and nothing after it merges.
  `.github/workflows/build_and_test.yml` calls the action in 18 jobs: the precondition job, and
  17 that pass `ref: needs.precondition.outputs.head_sha`, an input the action ignores outside
  apache/spark.
  No Varka workflow merges master into a branch.
* **The base the change is measured against is computed once per job, from HEAD.** The action's
  step "Find the base the branch's change is measured against" fetches vecbricks/varka's master
  and sets `VARKA_DIFF_BASE` to its merge base with HEAD, and `VARKA_DIFF_HEAD` to HEAD. Change
  detection (`dev/is-changed.py`, `dev/run-tests.py`, the "Check changes" job), the size cap
  (`dev/varka_msg_check.py size --base`), the emitted-bytes check (`git diff "$base" HEAD`), the
  scoped-test listing (`dev/varka_new_tests.py`) and the commit-message range
  (`git merge-base HEAD refs/varka/master`) all read HEAD or these two variables;
  `dev/varka_new_tests.py`'s base, listing and `fails-on-base` overlay diff against HEAD itself.
* **A merge would rarely conflict.** The last 60 merged pull requests' heads, each merged with
  master as it stood just before its merge (`results/VARKA-590-merges.txt`): 13 already contained
  master, and 47 lacked 1 to 26 of its commits. All 47 merge without a conflict, with the
  varka-generated merge driver configured. That is an undercount for earlier pushes, since the
  heads that merged often had master merged in by hand.
* **What the check would have rejected.** Merging in each job against master's head at that moment.
  Master moves while branches build (the 47 heads above lacked up to 26 commits), so two jobs of
  one run could test two different trees.

## 3. The design

### 3.1 One master commit per run, merged in every job

The precondition job's call of the action fetches vecbricks/varka's master once and outputs its
commit as `varka_master`. It does not merge: its checkout stays the pushed commit, so its
`head_sha` output, which the "Admit a full-matrix run" job checks out with `actions/checkout`,
names a commit GitHub has. The action takes a new input, `master`, which the 17 other jobs pass
as `needs.precondition.outputs.varka_master`. Every one of those jobs then:

1. checks out `github.sha` as today;
2. installs the varka-generated merge driver (`dev/varka_precommit.sh --install-merge-driver`,
   a narrower form of `--install-hook` that installs no hook);
3. unless `master` is already an ancestor of the head, or the branch is `master`, merges it with
   `git merge --no-ff --no-edit`, with the author, committer and date fixed to the head's. The
   merge commit is then the same commit in every job of the run;
4. on a conflict, runs `git merge --abort`, keeps the head, and prints
   `::warning title=Not merged with master::<files>; this Build tests the head alone`. Every job
   meets the same conflict, so the run tests one tree either way;
5. after a clean merge, runs `dev/varka_generated_merge.py --owed`. The driver leaves some
   generated files owed (`option_arms`, the docs counts) until `dev/varka_regen_generated.sh`
   runs, which needs sbt that most jobs never start, and the Build's staleness checks would then
   fail a tree the branch never committed. So a merge that owes anything is undone with
   `git reset --hard` to the head, and the job falls back with the same warning, naming the owed
   files. The owed list is the same in every job, so this too gives one tree per run.

`head_sha` stays the checked-out commit before any merge, as today. A new output, `tree_sha`,
is the merge's commit or the head, and `merged` says which (`true` or `false`). The run's summary
says which tree was built.

### 3.2 The pull request's own change is measured as before

In "Find the base", `VARKA_DIFF_HEAD` becomes the branch's head (`github.sha`) and `VARKA_DIFF_BASE`
its merge base with the pinned master. These are the values they have today, so change detection and
every reader of the two variables see the same diff. The readers that diff against HEAD directly are
pointed at `VARKA_DIFF_HEAD`: the size cap (`size --head "$VARKA_DIFF_HEAD"`, with `size_findings`
reading the branch name from the environment for any head, so a sync branch is still recognised),
the emitted-bytes check, the commit-message range, `dev/varka_new_tests.py`, which lists and reruns
the branch's new tests and would otherwise count master's as the branch's,
`dev/varka_matrix_pick.py` (its `git diff -U0 <base> HEAD`, which picks the matrix configuration
through `dev/varka_matrix_ci.sh`), the "Config binding policy exceptions check" (`git diff
"$APACHE_SPARK_REF" HEAD`, which would fail every merged branch on an entry a master commit added),
and change detection's fallback for unset variables (`git diff --name-only "$APACHE_SPARK_REF"
HEAD`). A grep of `.github/workflows` and `dev/varka_*` for HEAD-relative diffs found no other
reader that CI runs on the checkout. The `range` check already skips merge commits (`--no-merges`),
so the synthetic merge is never checked as a commit of the branch.

Alternatives considered:

* **A base of the pinned master and a head of the merge**, so that HEAD-relative readers need no
  edit. Rejected: where master and the branch touch the same lines, the diff is the merge's, not
  the pull request's, and a sync branch's own-commit count would include master's merge.
* **GitHub's `refs/pull/N/merge`.** Rejected: the Build runs on a push to the fork, where no pull
  request ref exists, and that ref merges into the pull request's base at GitHub's moment, not
  the run's.
* **Merging only in the Varka-scoped jobs.** Rejected: the size, lint and Spark jobs would then
  build another tree than the tests.

### 3.3 What is deliberately unchanged

apache/spark's path. The option matrix's checkout (`varka-option-matrix.yml`): it reuses the
Build's test classes, which are now the merged tree's, but its scripts come from the head. That
is the risk in 7.2, and it is worth a row of its own if it bites. `maven_test.yml` and
`python_hosted_runner_test.yml`, which call the action on their own triggers and pass no
`master`. With no `master` given, the action does what it does today.

## 4. Files

| file | what |
|---|---|
| `.github/actions/checkout-and-sync/action.yml` | `master` input, merge, fallback, outputs |
| `.github/workflows/build_and_test.yml` | `varka_master`, 17 call sites, 5 HEAD readers |
| `dev/varka_precommit.sh` | `--install-merge-driver` |
| `dev/varka_msg_check.py` | the branch name for `size --head` |
| `dev/varka_new_tests.py`, `dev/varka_matrix_pick.py` | the head from `VARKA_DIFF_HEAD` |
| `dev/varka_ci_merge_test.sh` | the local test of 5 |
| `.github/workflows/README.md` | what the action checks out |

## 5. Tests, and what each is for

* `dev/varka_ci_merge_test.sh`: it runs the action's merge script (moved into
  `dev/varka_ci_checkout.sh` so a shell can run it) in a scratch clone over five branches. A branch
  that lacks a master commit is merged, and the merge's commit is identical on two runs. A branch
  that contains master is left alone. A branch that conflicts falls back with the warning line.
  A branch that changes `sql/varka/types.tsv` beside master's change merges through the driver.
  A branch that adds `coverage.json` rows beside master's, so the merge owes a regeneration,
  falls back with the owed files named. These catch a non-deterministic merge, which would split
  one run over two trees, a missing driver, which would turn every generated-file edit into a
  fallback, and a merged tree that the staleness checks would fail.
* `python3 dev/varka_msg_check.py --selftest`, with a case for `size --head <sha>` on a sync
  branch.
* `actionlint` over the two workflow files.

## 6. The measurement

### 6.1 Predictions, registered before the run

1. A replay of #1413's case: a scratch branch cut from `d1e1a2c48f0^1`, the commit before #1416's
   fix, with a one-line comment in a catalyst Varka file. Its Build merges master, says
   `merged=true` in its summary, and passes `VarkaWidthAuditSuite`. On the head alone, the suite
   hung, as #1413's did.
2. The same run's size cap and "Check changes" read the branch's one-line change: one changed
   line and the catalyst modules, not master's commits since `d1e1a2c48f0^1`.
3. The merge adds under 30 s to a job: one fetch of master, which the action already makes,
   and one merge of a few commits.

## 7. Risks

1. **A conflict on every push of a long branch.** Its Builds fall back to the head with the
   warning, which is today's behaviour plus a notice. The notice is the cue to merge master by
   hand.
2. **The option matrix after a merged Build** runs the merged tree's classes with the head's
   `skips.tsv` and scripts. A master change to `skips.tsv` that its own classes need would then be
   missing. Step 4's dispatch shows whether this happens on a real branch.
3. **The `tree_sha` output names a commit that exists only on runners.** Nothing checks it out:
   the jobs check out `github.sha` and merge themselves, and `head_sha`, which the admission job
   checks out, stays the pushed commit. `update_build_status` matches runs by the run's
   `head_sha`, which is the pushed commit too.
4. **A merge that owes a regeneration falls back**, so a branch beside a master change to the
   same generated rows tests its head alone until master is merged by hand, as on a conflict.

## 8. Sequencing

One pull request, about 250 counted lines.

1. The merge script, `--install-merge-driver` and the local test. Done when
   `dev/varka_ci_merge_test.sh` passes its five branches.
2. The action and the workflow: the input, the outputs, 17 call sites and the five HEAD readers.
   Done when `actionlint` passes and `python3 dev/varka_msg_check.py --selftest` passes.
3. On the owner's word, the pull request's own Build, then the scratch branch of prediction 1
   pushed to the fork. Done when the scratch Build passes on the merged tree, with predictions 1
   and 2 scored from its logs.
4. On the owner's word, one option-matrix dispatch on the scratch branch. Done when it is green or
   7.2 is written up as a row.

## 9. Questions for the owner

1. **Merge in every job or build once?** Recommended: merge in every job, from one pinned master
   commit. It keeps each job's checkout self-contained, as today, and costs seconds. Building
   the merge once and handing it on would need an artifact that each of 17 jobs downloads.
   This is a cost call only the owner can weigh against the fork's slots and minutes. The plan is
   built on merging in every job.
2. **A conflict: fall back or fail?** Recommended: fall back with the warning, as the row's
   done-when says. Failing would block a branch for a reason its own change may not cause. This
   is listed because failing is the stricter reading of "test the merge".

## 10. Done when

A pull request's Build checks out the merge of its head into Varka's current master, never
apache/spark's. It falls back to the head with a visible notice when the merge conflicts. Its
change detection and size check still measure the pull request's own change. A branch that
predates a master fix passes on the merged tree.
