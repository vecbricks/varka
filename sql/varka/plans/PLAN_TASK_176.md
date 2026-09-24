# Task 176: a CI queue script

## 1. Where this came from

Milestone 6 row 176, from `SCOPE_MILESTONE_7.md` item 44. The fork that runs
this project's CI (MaxGekk/spark) runs at most twenty GitHub Actions jobs at
once, and one Spark Build run is about thirty-five, so two Builds in flight
share the slots and neither finishes until both do. The standing rule is one
Build at a time, in the order the pull requests will merge. It has been kept by
hand and by scratch scripts written afresh for each stack of PRs: one that
cancels a new PR's automatic run, one that waits for the run ahead and then
reruns the held one, one that prints which PR is ready. The session that
landed task 87 accumulated thirty-four of them (`ci-chain*.sh`, `watch*.sh`),
and one was lost with the process that ran it, leaving #342's run cancelled
with nothing to rerun it.

## 2. The admission check, done

The two facts the script rests on, checked against the fork on 24 September
2026:

* A cancelled run can be rerun whole with `gh run rerun <id>`, and its id is
  kept, so a queue can hold run ids: #342's run 35937074618, cancelled at
  00:09 UTC, is the run the first live `run` reran.
* `gh run list --workflow Build --branch <b>` finds a PR's runs on the fork by
  the PR's head branch, which `gh api repos/vecbricks/varka/pulls/<n>` gives
  along with the head's repository and sha. So nothing about the fork needs
  configuring: `.head.repo.full_name` names it.

What it would have rejected: a design that waits with `gh run watch`, which
returns at once without a terminal - the failure item 44 names.

## 3. The design

### 3.1 One queue, four verbs

`dev/varka_ci_queue.sh` keeps a queue of `<pr> <run id>` lines in a file in the
clone's git directory (`git rev-parse --git-common-dir`), so every worktree of a
clone shares it and nothing is committed.

* `hold <pr>...` - after a push: waits up to three minutes for the push's Build
  to appear, cancels it if it is running, and appends the PR (a PR already
  queued keeps its place). A run that already passed at the PR's head is not
  held.
* `run` - works through the queue in order. For each PR: skip it if it merged
  or closed; use its newest run at its current head; if that run needs a
  rerun, wait until no Build is queued or running anywhere on the fork, rerun
  it, and wait for it to complete; print the verdict and, for a failure, each
  failed job's failed steps. One runner per clone, by `flock`.
* `drop <pr>...` - after a merge: takes the PR off the queue and cancels its
  running Build, whose result is worth nothing and which still holds slots.
* `status` - every open PR with its newest Build run, that run's state, whether
  the run is for the PR's current head, and its place in the queue.

Every wait keys on a run's `completed` status, so a cancellation or a timeout
ends it as surely as a pass, and every wait has a deadline
(`VARKA_CI_WAIT_MINUTES`, 240). The script prints `EXIT <status>` from a trap
on every path, so a script that waits on it can key on that line;
`run`'s exit status is the number of failed runs. A waiter keys on a terminal
state the producer guarantees, never on a success-only marker.

### 3.2 What is deliberately unchanged

The CI workflows themselves: task 177 owns a scoped CI path. The merge order is
the caller's - the queue runs PRs in the order they were held, and does not try
to infer a stack from base branches, which on this project are all `master`.

### 3.3 Registered op counts

None: no emitter change.

## 4. Files

| file | what |
|---|---|
| `dev/varka_ci_queue.sh` | the script |
| `CONTRIBUTING.md` | names it beside the other contributor tools |
| `PLAN_MILESTONE_6.md` | row 176 |

## 5. Tests, and what each is for

A shell script against a live GitHub fork has no hermetic harness here, so it
is checked the way the other `dev/` tools were: `bash -n` and `shellcheck -S
warning` clean, and each verb run once for real on the live queue, recorded in
section 9. The guards are checked directly: a second `run` refuses while one
holds the queue, and a verb without its arguments prints the usage.

## 6. The measurement

None: no performance claim.

## 7. Risks

1. **A push to a held PR while `run` is waiting on another** starts a second
   Build beside it; the queue does not see pushes. The answer is to `hold` after
   every push, which the usage says, and `status` shows the second run.
2. **The queue can change under a long wait.** A `run` that picked a PR and
   then waited an hour for the fork to go idle must not rerun it if the PR was
   dropped or held again meanwhile, and must not take a PR re-held while its
   run went off the queue on the old run's verdict. It re-reads the PR's queue
   line after each wait and acts only if the line still names the run it
   started with. *Added 24 September 2026: the first version did not, and
   reran #342's Build after #342 had been dropped for a fix.*
3. **GitHub's run list is eventually consistent**: a rerun takes a few seconds
   to leave `completed`, so `run` sleeps thirty seconds after a rerun before its
   first poll. Without that it would read the old conclusion and move on.

## 8. Sequencing

One commit: the script, `CONTRIBUTING.md` and row 176, after the live run of
section 9.

## 9. Outcome

The script's first live use was the one it was written for: task 87's stack,
#341's Build running and #342's cancelled.

* `status` listed the three open PRs with #342's run cancelled at its head.
* `hold 342` found run 35937074618 cancelled at the PR's head and queued it.
* `run` waited on #341's run 35937052775 ("waiting for run 35937052775 on
  varka-task-87-both-forms"), and a second `run` started beside it refused.

The rest of that run's record - the rerun and #342's verdict - is in the pull
request that lands this, since it completes after the commit. The scratch
scripts of task 87 are deleted; they were never in the repository.
