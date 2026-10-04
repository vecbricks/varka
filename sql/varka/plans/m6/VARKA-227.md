# VARKA-227: the CI queue's misleading checks, its exits, and the machine it runs on

## 1. Where this came from

Milestone 6 row 227. `dev/varka_ci_queue.sh` (VARKA-176) keeps the fork's CI to one
Build at a time by cancelling the run a push starts and rerunning it in turn. Three
frictions with it, in the order they were met:

1. **The pull request's check is stale.** GitHub shows the cancelled attempt as the
   PR's `Build` check until `update_build_status.yml` on the base repository copies
   the rerun's result over it. That workflow is on a fifteen-minute cron, and GitHub
   started it only every few hours on 27 September 2026: #444 and #447 read "Build:
   cancelled" for hours after passing. The cancelled attempt itself is confusing to
   open: its first job's checkout was cut short, and the post step then reports
   "Can't find `action.yml` ... Did you forget to run actions/checkout", which reads
   as a broken workflow (#497, 28 September 2026).
2. **`run` exits when the queue empties**, so a `hold` made later waits until someone
   restarts the runner, which happened by hand several times a day.
3. **The runner is a process on the owner's laptop.** On 28 September 2026 the laptop
   slept from 08:12 to 17:00; #492's rerun finished at 08:26 with nobody to see it,
   and on waking the runner found its four-hour deadline passed and exited with
   "timeout", the run long complete. Three pull requests waited the day. A watchdog
   that restarts an exited runner does nothing for one that sleeps.

## 2. The change

Two of the three are fixed in the script, and the third is narrowed to what a laptop
process can do:

* **The sync is asked for.** `update_build_status.yml` accepts `workflow_dispatch`, and
  `run` dispatches it when a rerun completes, so the PR's check shows the rerun's
  result within a minute instead of at the cron's next start. Best effort: the cron
  remains, and the dispatch reports when it cannot be made (before the workflow file
  with the trigger is on the base repository's default branch, for one).
* **`run --while-open` stays up.** When the queue empties it idles, polling every
  `VARKA_CI_IDLE` seconds (180) for a later `hold`, and exits only when no pull request
  is open. The lock, the one-runner rule and every wait are unchanged.
* **A deadline is checked against the run, not the clock.** `wait_completed` and
  `wait_fork_idle` ask GitHub once more when they find the deadline passed, and take a
  completed run as completed. A runner that slept through a run now continues with the
  next PR on waking, where before it exited. It cannot start the next PR while asleep;
  nothing on the laptop can.

What is not changed: the cancelled attempt's own page. Cancelling during the first
job's checkout is what a hold is, and the post step's message is GitHub's.

## 3. What remains, and the decision it needs

The queue's dispatcher should not be a process on a laptop at all. The design that
removes friction 3, for the owner to decide, since it needs a credential:

* A workflow on the base repository, on a five-minute cron and `workflow_dispatch`,
  with no queue file: every open pull request whose head has no passed Build and is
  not covered by a docs-only rule is in the queue, in pull request number order (the
  order they merge in), and a label such as `ci-hold` takes one out. Each tick: if a
  Build is in progress on the fork and it is the first PR's, wait; if it is another's,
  cancel it; if none is, rerun the first PR's newest run at its head; when a run has
  completed, update the PR's check directly (the workflow has `checks: write` on its
  own repository, which is all `update_build_status.yml` uses).
* It acts on the fork's runs - cancel, rerun - which the base repository's
  `GITHUB_TOKEN` cannot do. It needs a fine-grained personal access token on
  `MaxGekk/spark` with `actions: read and write`, stored as a repository secret of
  `vecbricks/varka`. That token is the owner's to create.
* `hold` and `drop` then become label edits or nothing: a push is picked up on the
  next tick, and a merge takes the PR out of the queue by itself.

Until then the laptop runner with `--while-open` and a supervisor is the queue, and it
stops when the laptop does.

## 4. Verification

* `bash -n` on the script; the `--while-open` flag and an unknown flag parse as
  intended; `list` and `status` unchanged.
* The dispatch is exercised by the first rerun that completes after this merges: the
  runner prints "asked vecbricks/varka to sync the Build check" and the PR's check
  changes within a minute. Before the merge it prints the fallback line, since the
  trigger is not yet on the default branch.
* The wake-up path: the next time the laptop sleeps through a run, the runner's log
  shows the run's verdict after the wake rather than "timeout".

## 5. Outcome, 1 October 2026

Of section 4's three checks, two have run. The script's flags parse and `list` and
`status` are unchanged, from the build. The dispatch is exercised: the runner asked for
the sync after its reruns on 30 September, and on 1 October the Build check of #534
followed each of eight dispatches of `update_build_status.yml` within a minute, where on
27 September a check waited hours for the cron. The wake-up path has not been
exercised, since no run has been slept through since the merge; it keeps section 2's
rule, every wait keyed on a completed status and a deadline, and the first sleep that
meets it will say.

Row 227 is done on what it set out to build. Section 3's design, the queue as a
workflow on the base repository that needs a token of the owner's, is
`m8/SCOPE.md` item 76, so that the decision it needs has a place to be made.
